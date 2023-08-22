package com.example.cfsignedurl.service;

import jakarta.annotation.PostConstruct;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest;
import software.amazon.awssdk.services.cloudfront.internal.auth.Pem;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;



@Service
public class AWSS3ServiceSDKVersionTwo {

    @Autowired
    private S3Client s3Client;

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
     * The ID of the CloudFront distribution public key
     * Value is available in Public Keys section in CloudFront
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
     * this will be generated at the startup(at postconstruct) by fetching the private key content from
     * AWS Parameter Store
     */
    private PrivateKey cloudFrontDistributionPrivateKey;

    private final String TEMP_FILE_KEY_PREFIX = "s3-temp-file";
    private final String TEMP_FILE_KEY_SUFFIX = "csv";

    private static final Logger logger = LoggerFactory.getLogger(AWSS3ServiceSDKVersionTwo.class);


    public String getCloudFrontSignedUrl(String requestIdentifier) {

        String s3FilePath = new StringBuilder()
                .append(String.format("testfolder/%s%s.csv", "sample-file-", requestIdentifier)).toString();

        // add dummy data line to file
        String csvString = requestIdentifier + "," + "abcd";
        InputStream fileContentStream = new ByteArrayInputStream(csvString.getBytes(Charset.forName("UTF-8")));

        File tempFile = null;
        try {
            tempFile = File.createTempFile(TEMP_FILE_KEY_PREFIX, TEMP_FILE_KEY_SUFFIX);
            FileUtils.copyInputStreamToFile(fileContentStream, tempFile);

            boolean uploadedStatus = uploadObjectToAmazonS3(tempFile, s3FilePath);

            String cloudFrontSignedUrl = null;
            if (uploadedStatus) {
                cloudFrontSignedUrl = generateCloudFrontSignedUrlByS3ObjectPath(s3FilePath);
            } else {
                logger.warn("file: {} upload failed", s3FilePath);
            }
            return cloudFrontSignedUrl;

        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to upload file due to an IO exception"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    public Boolean uploadObjectToAmazonS3(File file, String s3FilePath) {
        boolean isSuccessfulllyUploded = true;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(s3Bucket)
                .key(s3FilePath)
                .build();

        try {
            s3Client.putObject(putObjectRequest, RequestBody.fromFile(file));
        } catch (AwsServiceException awsServiceException) {
            isSuccessfulllyUploded = false;
        } catch (SdkClientException sdkClientException) {
            isSuccessfulllyUploded = false;
        }

        logger.info("file: {} uploaded status: {}", s3FilePath, isSuccessfulllyUploded);
        return isSuccessfulllyUploded;
    }

    /**
     * The official documentation for java CloudFront sdk version 2 is available at
     * https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/CFPrivateDistJavaDevelopment.html
     * https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/java_cloudfront_code_examples.html
     *
     *
     * Instead of maintaining the private key file physically, we maintain the content of the private key
     * in AWS parameter store and construct the PrivateKey object in the following postcontruct invocation.
     */
    @PostConstruct
    private void constructCloudFrontDistributionPrivateSignKey(){
        // Approach One: Use PKCS#1 PEM key
        // (Since the AWS CloudFront supports both PKCS#1 PEM keys and DER encoded PKCS#8 private keys)
        InputStream is = new ByteArrayInputStream(cloudfrontDistributionPrivateSignKey.getBytes(StandardCharsets.UTF_8));
        try {
            cloudFrontDistributionPrivateKey =  Pem.readPrivateKey(is);
        } catch (InvalidKeySpecException e) {
            throw new RuntimeException(String.format("Failed to generate the Cloudfront private key due to a KeySpec Exception"));
        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to generate the Cloudfront private key due to an IO exception"));
        } finally {
            try {is.close();} catch(IOException ioEx) {logger.error("Error occurred when trying to clean the resources created for reports cloudfront private key. ERROR: {}", ioEx.getMessage());}
        }

        // Apart from above approach, two other approaches are available as mentioned in
        // alternativeApproachesToGetCloudFrontDistributionPrivateSignKey() method block that is commented out
        // in the end of this class.
        // Sharing them, in case the PKCS#1 PEM key support is dropped in the future.
    }

    /**
    * Generate CloudFront signed URL for given S3 object path
    */
    public String generateCloudFrontSignedUrlByS3ObjectPath(String s3FilePath) throws Exception {
        String protocol = "https";

        // the wild card replacement is to avoid errors that occur due to spaces in S3 object paths
        // eg: "test folder/sample file.csv" will be converted to "test+folder/sample+file.csv" before requesting the
        // signed url from CloudFront
        String resourcePath = "/" + s3FilePath.replaceAll("\\s", "+");

        String cloudFrontResourceURL = new URL(protocol, cloudfrontDistributionDomainName, resourcePath).toString();
        Instant expirationDate = Instant.now().plus(signedUrlRetensionDurationDays, ChronoUnit.DAYS);

        CannedSignerRequest cannedSignerRequest = CannedSignerRequest.builder()
                                                                    .resourceUrl(cloudFrontResourceURL)
                                                                    .privateKey(cloudFrontDistributionPrivateKey)
                                                                    .keyPairId(cloudfrontDistributionKeyPairId)
                                                                    .expirationDate(expirationDate)
                                                                    .build();

        return CloudFrontUtilities.create().getSignedUrlWithCannedPolicy(cannedSignerRequest).url();
    }

     /**
     * other approaches to generate Cloudfront signed URLs
     * are mentioned in this method which are commented out
     */
    private void alternativeApproachesToGetCloudFrontDistributionPrivateSignKey(){
        /* //comment block start
        // Approach Two:
        // Use bouncycastle library to generate DER encoded PKCS#8 private key object
        // From the generated KeyPair we can, extract the DER encoded PrivateKey object
        // and use that PrivateKey object in CannedSignerRequest builder
        //
        // Apart from that we can save the DER encoded PKCS#8 private key to a temp location and use that
        // DER encoded file in CannedSignerRequest builder since the Path to the key file can be passed,
        // and loads it, to return a PrivateKey object
        // This approach leads to maintaining a file in the server
        // reference: https://stackoverflow.com/questions/72818880/convert-rsa-private-key-to-der-format-in-java

        try ( PEMParser pemParser = new PEMParser(new StringReader(cloudfrontDistributionPrivateSignKey));) {
            // Import PEM encoded PKCS#1 private key
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            KeyPair keyPair = converter.getKeyPair((PEMKeyPair)pemParser.readObject());
            cloudFrontDistributionPrivateKey =  keyPair.getPrivate();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        */ //comment block end


        /*  //comment block start
        // Approach Three: Use JetS3t library as specified in AWS CloudFront docs.
        // https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/CFPrivateDistJavaDevelopment.html
        //
        // With JetS3t library the content of the DER encoded PKCS#8 private key file can be extracted.
        // Note that JetS3t library is no longer maintained. But forks are available.
        //
        // In the following example we are using a PEM encoded PKCS#1 private key file to generate a DER
        // encoded PKCS#8 private key file in the /tmp directory using bouncycastle.
        // Instead of that we can use openssl to generate the DER encoded PKCS#8 private key file using following command.
        // openssl pkcs8 -topk8 -nocrypt -in origin.pem -inform PEM -out new.der -outform DER
        //
        // But we won't be able to maintain the DER encoded PKCS#8 private key file content in
        // AWS parameter store since the DER private key file is a binary file.
        // reference: https://stackoverflow.com/questions/72818880/convert-rsa-private-key-to-der-format-in-java

        String derPrivateKeyFilePath = "/tmp/private_key.der";
        File derPrivateKeyFile = new File(derPrivateKeyFilePath);
        byte[] derPrivateKey = null;  // maintain this in class level so that it can be reused for cloudfront signed url generation
        try ( PEMParser pemParser = new PEMParser(new StringReader(cloudfrontDistributionPrivateSignKey));
              FileOutputStream outputStream = new FileOutputStream(derPrivateKeyFilePath)
        ) {
            // Import PEM encoded PKCS#1 private key
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            KeyPair keyPair = converter.getKeyPair((PEMKeyPair)pemParser.readObject());
            // Export DER encoded PKCS#8 private key
            byte[] privateKey = keyPair.getPrivate().getEncoded();
            outputStream.write(privateKey, 0, privateKey.length);
            cloudFrontDistributionPrivateKey =  keyPair.getPrivate();
            derPrivateKey = ServiceUtils.readInputStreamToBytes(new
                    FileInputStream(derPrivateKeyFilePath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (derPrivateKeyFile.exists()) {
              derPrivateKeyFile.delete(); // clean the private key
            }
        }

        // CloudFront signed url generation via JetS3t library.
        // Note that in this library we parse the Private key data as byte[], instead of PrivateKey object.
        Instant instant = Instant.now();
        Date dateLessThan = DateUtils.parseISO8601Date(instant.plus(Duration.ofDays(signedUrlRetensionDurationDays)).toString());
        String signedUrlCanned = CloudFrontService.signUrlCanned(
                new URL("https", cloudfrontDistributionDomainName, "/s3/path").toString(), // Resource URL or Path
                cloudfrontDistributionKeyPairId,     // Certificate identifier,
                // an active trusted signer for the distribution
                derPrivateKey, // DER Private key data
                dateLessThan
        );
        */ // comment block end
    }

}
