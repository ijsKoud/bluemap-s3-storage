package nl.klrnbk.bluemap.s3.client;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Vectors from the AWS Signature Version 4 documentation and the AWS sigv4 test suite. */
class SigV4SignerTest {

    @Test
    void awsS3GetObjectWithRangeExample() {
        // https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html (GET Object)
        var signer = new SigV4Signer("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "s3");
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        headers.put("x-amz-date", "20130524T000000Z");
        String auth = signer.authorization("GET", "/test.txt", Map.of(), headers,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Instant.parse("2013-05-24T00:00:00Z"));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + " SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + " Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", auth);
    }

    @Test
    void awsS3PutObjectExample() {
        // Same documentation page: PUT Object "Welcome to Amazon S3." with storage class header
        var signer = new SigV4Signer("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "s3");
        String hash = "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072";
        Map<String, String> headers = new TreeMap<>();
        headers.put("date", "Fri, 24 May 2013 00:00:00 GMT");
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("x-amz-content-sha256", hash);
        headers.put("x-amz-date", "20130524T000000Z");
        headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
        String auth = signer.authorization("PUT", "/test%24file.text", Map.of(), headers, hash,
                Instant.parse("2013-05-24T00:00:00Z"));
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
                auth.substring(auth.indexOf("Signature=") + 10));
    }

    @Test
    void awsS3ListObjectsExample() {
        var signer = new SigV4Signer("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "s3");
        String hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("x-amz-content-sha256", hash);
        headers.put("x-amz-date", "20130524T000000Z");
        String auth = signer.authorization("GET", "/", Map.of("max-keys", "2", "prefix", "J"), headers, hash,
                Instant.parse("2013-05-24T00:00:00Z"));
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
                auth.substring(auth.indexOf("Signature=") + 10));
    }

    @Test
    void awsSigV4SuiteGetVanilla() {
        var signer = new SigV4Signer("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "us-east-1", "service");
        Map<String, String> headers = new TreeMap<>();
        headers.put("Host", "example.amazonaws.com");
        headers.put("X-Amz-Date", "20150830T123600Z");
        String auth = signer.authorization("GET", "/", Map.of(), headers, SigV4Signer.sha256Hex(new byte[0]),
                Instant.parse("2015-08-30T12:36:00Z"));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request,"
                + " SignedHeaders=host;x-amz-date,"
                + " Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", auth);
    }

    @Test
    void awsSigV4SuiteGetVanillaQueryOrder() {
        var signer = new SigV4Signer("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "us-east-1", "service");
        Map<String, String> headers = new TreeMap<>();
        headers.put("Host", "example.amazonaws.com");
        headers.put("X-Amz-Date", "20150830T123600Z");
        String auth = signer.authorization("GET", "/", Map.of("Param1", "value1", "Param2", "value2"), headers,
                SigV4Signer.sha256Hex(new byte[0]), Instant.parse("2015-08-30T12:36:00Z"));
        assertEquals("b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500",
                auth.substring(auth.indexOf("Signature=") + 10));
    }

    @Test
    void uriEncoding() {
        assertEquals("a%20b/c%2Bd~e.f-g_h", SigV4Signer.uriEncode("a b/c+d~e.f-g_h", false));
        assertEquals("a%2Fb", SigV4Signer.uriEncode("a/b", true));
        assertEquals("%C3%A9", SigV4Signer.uriEncode("é", true));
    }
}
