/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.aws.proxy.server.signing;

import com.google.common.annotations.VisibleForTesting;
import io.airlift.log.Logger;
import io.trino.aws.proxy.server.signing.Signers.SigningApi;
import io.trino.aws.proxy.spi.credentials.Credential;
import io.trino.aws.proxy.spi.rest.RequestContent;
import io.trino.aws.proxy.spi.signing.ChunkSigningSession;
import io.trino.aws.proxy.spi.signing.RequestAuthorization;
import io.trino.aws.proxy.spi.signing.SigningContext;
import io.trino.aws.proxy.spi.signing.SigningServiceType;
import io.trino.aws.proxy.spi.util.AwsTimestamp;
import io.trino.aws.proxy.spi.util.ImmutableMultiMap;
import io.trino.aws.proxy.spi.util.MultiMap;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.UriBuilder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.signer.internal.Aws4SignerRequestParams;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.auth.signer.params.AwsS3V4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiFunction;

import static io.trino.aws.proxy.server.signing.Signers.OVERRIDE_CONTENT_HASH;
import static io.trino.aws.proxy.server.signing.Signers.awsS3V4Signer;
import static io.trino.aws.proxy.server.signing.Signers.awsV4Signer;
import static io.trino.aws.proxy.server.signing.Signers.legacyS3AwsV4Signer;
import static io.trino.aws.proxy.spi.rest.RequestContent.ContentType.AWS_CHUNKED;
import static io.trino.aws.proxy.spi.rest.RequestContent.ContentType.AWS_CHUNKED_IN_W3C_CHUNKED;
import static io.trino.aws.proxy.spi.signing.SigningTrait.S3V4_SIGNER;
import static io.trino.aws.proxy.spi.signing.SigningTrait.STREAM_CONTENT;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.UNAUTHORIZED;
import static java.util.Objects.requireNonNull;

final class Signer
{
    private static final Logger log = Logger.get(Signer.class);

    // https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html
    // Presigned SigV4 requests have a maximum length of 7 days as per spec
    // The AWS SDK will throw if we attempt to build anything longer
    @VisibleForTesting
    static final Duration MAX_PRESIGNED_REQUEST_AGE = Duration.ofDays(7);

    private Signer() {}

    static byte[] signingKey(AwsCredentials credentials, Aws4SignerRequestParams signerRequestParams)
    {
        return awsS3V4Signer.signingKey(credentials, signerRequestParams);
    }

    static SigningContext presign(
            SigningServiceType serviceType,
            URI requestURI,
            SigningHeaders signingHeaders,
            MultiMap queryParameters,
            String region,
            Instant requestDate,
            Instant requestExpiry,
            String httpMethod,
            Credential credential,
            Duration maxClockDrift,
            RequestContent requestContent)
    {
        Duration requestToExpiry = Duration.between(requestDate, requestExpiry);
        if (requestToExpiry.isNegative() || requestToExpiry.compareTo(MAX_PRESIGNED_REQUEST_AGE) > 0) {
            log.debug("Presigned request expiry is inconsistent with request timestamp. RequestTime: %s Expiry: %s", requestDate, requestExpiry);
            throw new WebApplicationException(BAD_REQUEST);
        }
        enforceMaxDrift(requestDate, MAX_PRESIGNED_REQUEST_AGE, maxClockDrift);
        Aws4PresignerParams.Builder presignerParamsBuilder = Aws4PresignerParams.builder().expirationTime(requestExpiry);

        return internalSign(
                (signingApi, requestToSign) -> {
                    SdkHttpFullRequest signedRequest = signingApi.presign(requestToSign, presignerParamsBuilder.build());
                    RequestAuthorization requestAuthorization = SigningQueryParameters.splitQueryParameters(ImmutableMultiMap.copyOf(signedRequest.rawQueryParameters().entrySet()))
                            .toRequestAuthorization()
                            .orElseThrow(() -> {
                                log.debug("Presigner did not generate a valid request");
                                return new WebApplicationException(BAD_REQUEST);
                            });
                    return new InternalRequestAuthorization(requestAuthorization, signedRequest.getUri());
                },
                SdkHttpFullRequest.builder(),
                presignerParamsBuilder,
                serviceType,
                requestURI,
                signingHeaders,
                queryParameters,
                region,
                requestDate,
                httpMethod,
                credential);
    }

    static SigningContext sign(
            SigningServiceType serviceType,
            URI requestURI,
            SigningHeaders signingHeaders,
            MultiMap queryParameters,
            String region,
            Instant requestDate,
            String httpMethod,
            Credential credential,
            Duration maxClockDrift,
            RequestContent requestContent)
    {
        enforceMaxDrift(requestDate, maxClockDrift, maxClockDrift);
        boolean enableChunkedEncoding = requestContent.contentType() == AWS_CHUNKED || requestContent.contentType() == AWS_CHUNKED_IN_W3C_CHUNKED;
        AwsS3V4SignerParams.Builder signerParamsBuilder = AwsS3V4SignerParams.builder()
                .enablePayloadSigning(true)
                .enableChunkedEncoding(enableChunkedEncoding);
        SdkHttpFullRequest.Builder requestBuilder = SdkHttpFullRequest.builder();

        if (serviceType.hasTrait(STREAM_CONTENT)) {
            // because we stream content without spooling we want to re-use the provided content hash
            // so that we don't have to calculate it to validate the incoming signature.
            // Stash the hash in the OVERRIDE_CONTENT_HASH so that aws4Signer can find it and
            // return it.
            signingHeaders.getFirst("x-amz-content-sha256")
                    .ifPresent(contentHashHeader -> requestBuilder.putHeader(OVERRIDE_CONTENT_HASH, contentHashHeader));
        }
        else {
            requestContent.inputStream().ifPresent(inputStream -> requestBuilder.contentStreamProvider(() -> inputStream));
        }

        return internalSign(
                (signingApi, requestToSign) -> {
                    SdkHttpFullRequest signedRequest = signingApi.sign(requestToSign, signerParamsBuilder.build());
                    RequestAuthorization requestAuthorization = RequestAuthorization.parse(
                            signedRequest.firstMatchingHeader("Authorization").orElseThrow(() -> {
                                log.debug("Signer did not generate \"Authorization\" header");
                                return new WebApplicationException(BAD_REQUEST);
                            }),
                            credential.session());
                    return new InternalRequestAuthorization(requestAuthorization, signedRequest.getUri());
                },
                requestBuilder,
                signerParamsBuilder,
                serviceType,
                requestURI,
                signingHeaders,
                queryParameters,
                region,
                requestDate,
                httpMethod,
                credential);
    }

    private record InternalRequestAuthorization(RequestAuthorization requestAuthorization, URI signingUri)
    {
        private InternalRequestAuthorization
        {
            requireNonNull(requestAuthorization, "requestAuthorization is null");
            requireNonNull(signingUri, "signingUri is null");
        }
    }

    private static <R extends Aws4SignerParams.Builder<R>> SigningContext internalSign(
            BiFunction<SigningApi, SdkHttpFullRequest, InternalRequestAuthorization> authorizationBuilder,
            SdkHttpFullRequest.Builder requestBuilder,
            R paramsBuilder,
            SigningServiceType serviceType,
            URI requestURI,
            SigningHeaders signingHeaders,
            MultiMap queryParameters,
            String region,
            Instant requestDate,
            String httpMethod,
            Credential credential)
    {
        requestBuilder.uri(UriBuilder.fromUri(requestURI).replaceQuery("").build()).method(SdkHttpMethod.fromValue(httpMethod));

        signingHeaders.lowercaseHeadersToSign().forEach(entry -> entry.getValue().forEach(value -> requestBuilder.appendHeader(entry.getKey(), value)));

        queryParameters.forEach(requestBuilder::putRawQueryParameter);

        AwsCredentials credentials = credential.session()
                .map(session -> (AwsCredentials) AwsSessionCredentials.create(credential.accessKey(), credential.secretKey(), session))
                .orElseGet(() -> AwsBasicCredentials.create(credential.accessKey(), credential.secretKey()));
        paramsBuilder.signingName(serviceType.serviceName())
                .signingRegion(Region.of(region))
                .doubleUrlEncode(false)
                .awsCredentials(credentials);

        // because we're verifying the signature provided we must match the clock that they used
        Clock clock = Clock.fixed(requestDate, AwsTimestamp.ZONE);
        paramsBuilder.signingClockOverride(clock);

        SigningApi signer;
        if (serviceType.hasTrait(S3V4_SIGNER)) {
            signer = isLegacy(signingHeaders) ? legacyS3AwsV4Signer : awsS3V4Signer;
        }
        else {
            signer = awsV4Signer;
        }
        InternalRequestAuthorization internalRequestAuthorization = authorizationBuilder.apply(signer, requestBuilder.build());
        return buildSigningContext(
                internalRequestAuthorization,
                signingKey(credentials, new Aws4SignerRequestParams(paramsBuilder.build())),
                requestDate,
                signingHeaders.getFirst("x-amz-content-sha256"));
    }

    private static SigningContext buildSigningContext(InternalRequestAuthorization internalRequestAuthorization, byte[] signingKey, Instant requestDate, Optional<String> contentHash)
    {
        if (!internalRequestAuthorization.requestAuthorization.isValid()) {
            log.debug("Invalid RequestAuthorization. RequestAuthorization: %s", internalRequestAuthorization);
            throw new WebApplicationException(UNAUTHORIZED);
        }
        ChunkSigner chunkSigner = new ChunkSigner(requestDate, internalRequestAuthorization.requestAuthorization.keyPath(), signingKey);
        ChunkSigningSession chunkSigningSession = new InternalChunkSigningSession(chunkSigner, internalRequestAuthorization.requestAuthorization.signature());
        return new SigningContext(internalRequestAuthorization.requestAuthorization, chunkSigningSession, contentHash, internalRequestAuthorization.signingUri);
    }

    private static void enforceMaxDrift(Instant requestDate, Duration pastMaxClockDrift, Duration futureMaxClockDrift)
    {
        Instant now = Instant.now();
        Duration driftFromNow = Duration.between(now, requestDate);
        if ((driftFromNow.compareTo(pastMaxClockDrift.negated()) < 0) || (driftFromNow.compareTo(futureMaxClockDrift) > 0)) {
            log.debug("Request time exceeds max drift. RequestTime: %s Now: %s", requestDate, now);
            throw new WebApplicationException(BAD_REQUEST);
        }
    }

    private static boolean isLegacy(SigningHeaders signingHeaders)
    {
        return signingHeaders.hasHeaderToSign("user-agent");
    }
}
