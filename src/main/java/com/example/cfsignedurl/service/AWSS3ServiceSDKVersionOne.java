package com.example.cfsignedurl.service;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.PEM;
import com.amazonaws.services.cloudfront.CloudFrontUrlSigner;
import com.amazonaws.services.cloudfront.util.SignerUtils;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.util.DateUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static com.amazonaws.services.cloudfront.util.SignerUtils.generateResourcePath;

@Service
public class AWSS3ServiceSDKVersionOne {

    @Autowired
    private AmazonS3 amazonS3;

    /**
     * AWS S3 private bucket name
     */
    @Value("${s3.bucket}")
    private String s3Bucket;

    /**
     * PEM encoded PKCS#1 private key(.pem) file content
     */
    @Value("${aws.s3.object.cloudfront.private.sign.key}")
    private String cloudfrontDistributionPrivateSignKey;

    /**
     * The value of this variable will be taken from the
     * public keys section in CloudFront
     */
    @Value("${cloudfront.distribution.keypair.id}")
    private String cloudfrontDistributionKeyPairId;

    /**
     * cloudfront distribution's name without protocol(http/https)
     * eg: abcd.cloudfront.net
     */
    @Value("${cloudfront.distribution.domain}")
    private String cloudfrontDistributionDomainName;

    /**
     * generated CloudFront URL will be valid for
     * mentioned number of days starting from the creation date and time
     */
    @Value("${cloudfront.distribution.link-retention-duration-days}")
    private Long signedUrlRetensionDurationDays;

    /**
     * this will be generated at the startup by fetching the private key content from
     * AWS Parameter Store
     */
    private PrivateKey cloudFrontDistributionPrivateKey;

    private static final Logger logger = LoggerFactory.getLogger(AWSS3ServiceSDKVersionOne.class);


    public String getCloudFrontSignedUrl(String requestIdentifier) {

        String s3FilePath = new StringBuilder()
                .append(String.format("testfolder/%s%s.csv","sample-file-", requestIdentifier)).toString();

        // add dummy data line to file
        String csvString = requestIdentifier + "," + "abcd";

        InputStream fileContentStream = new ByteArrayInputStream(csvString.getBytes(Charset.forName("UTF-8")));

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("csv");

        boolean uploadedStatus = uploadObjectToAWSS3(fileContentStream, s3FilePath, metadata);

        String cloudFrontSignedUrl = null;
        if (uploadedStatus) {
            cloudFrontSignedUrl = generateCloudfrontSignedUrlByS3ObjectPath(s3FilePath);
        } else {
            logger.warn("file: {} upload failed", s3FilePath);
        }
        return cloudFrontSignedUrl;
    }

    public Boolean uploadObjectToAWSS3(InputStream fileContentStream, String s3FilePath, ObjectMetadata metadata) {
        boolean isSuccessfulllyUploded = true;
        try {
            amazonS3.putObject(new PutObjectRequest(s3Bucket, s3FilePath, fileContentStream, metadata));
        } catch (AmazonServiceException amazonServiceException) {
            isSuccessfulllyUploded = false;
        } catch (AmazonClientException amazonClientException) {
            isSuccessfulllyUploded = false;
        }

        logger.info("file: {} uploaded status: {}", s3FilePath, isSuccessfulllyUploded);
        return isSuccessfulllyUploded;
    }

    public String generateCloudfrontSignedUrlByS3ObjectPath(String s3FilePath) {
        SignerUtils.Protocol protocol = SignerUtils.Protocol.https;
        Instant instant = Instant.now();
        Date dateLessThan = DateUtils.parseISO8601Date(instant.plus(Duration.ofDays(signedUrlRetensionDurationDays)).toString());
        final String resourcePath = generateResourcePath(protocol, cloudfrontDistributionDomainName, s3FilePath);

        // the wild card replacement is to avoid errors that occur due to spaces in S3 object paths
        // eg: "test folder/sample file.csv" will be converted to "test+folder/sample+file.csv" before requesting the
        // signed url from CloudFront
        return CloudFrontUrlSigner.getSignedURLWithCannedPolicy(resourcePath.replaceAll("\\s", "+"), cloudfrontDistributionKeyPairId, cloudFrontDistributionPrivateKey, dateLessThan);
    }

    /**
     * The official documentation for java CloudFront sdk version 1 is available at
     * https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/cloudfront/CloudFrontUrlSigner.html
     *
     * Instead of maintaining the private key file physically, we have maintained the content of the private key
     * in AWS parameter store and recreated the PrivateKey object in the following postcontruct.
     */
    @PostConstruct
    private void getCloudFrontSignKey(){
        InputStream is = new ByteArrayInputStream(cloudfrontDistributionPrivateSignKey.getBytes(StandardCharsets.UTF_8));
        try {
            cloudFrontDistributionPrivateKey =  PEM.readPrivateKey(is);
        } catch (InvalidKeySpecException e) {
            throw new RuntimeException(String.format("Failed to generate the Cloudfront private key due to a KeySpec Exception"));
        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to generate the Cloudfront private key due to an IO exception"));
        } finally {
            try {is.close();} catch(IOException ioEx) {logger.error("Error occurred when trying to clean the resources created for reports cloudfront private key. ERROR: {}", ioEx.getMessage());}
        }
    }
}
