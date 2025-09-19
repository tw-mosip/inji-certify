package io.mosip.certify.credential;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.certify.api.dto.VCResult;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.vcformatters.VCFormatter;
import io.mosip.kernel.signature.dto.JWSSignatureRequestDtoV2;
import io.mosip.kernel.signature.dto.JWTSignatureResponseDto;
import io.mosip.kernel.signature.service.SignatureService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class SDJWTTest {

    @Mock
    private VCFormatter mockFormatter;

    @Mock
    private SignatureService mockSignatureService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private SDJWT sdjwt;

    @Before
    public void setup() {
        // MockitoJUnitRunner takes care of injecting mocks
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(sdjwt, "objectMapper", objectMapper);
    }

    // Helper method to call private validateSDPaths method using reflection
    private boolean callValidateSDPaths(String templatedJSON, List<String> expectedSDPaths) throws Exception {
        Method validateSDPathsMethod = SDJWT.class.getDeclaredMethod("validateSDPaths", String.class, List.class);
        validateSDPathsMethod.setAccessible(true);
        return (boolean) validateSDPathsMethod.invoke(sdjwt, templatedJSON, expectedSDPaths);
    }

    @Test
    public void testCanHandle_ShouldReturnTrueForCorrectFormat() {
        assertTrue(sdjwt.canHandle("vc+sd-jwt"));
    }

    @Test
    public void testCanHandle_ShouldReturnFalseForIncorrectFormat() {
        assertFalse(sdjwt.canHandle("ld+vc"));
    }

    @Test
    public void testCreateCredential_WithValidInput_ReturnsSdJwt() throws JsonProcessingException {
        String mockTemplateName = "mockTemplate";
        Map<String, Object> templateParams = new HashMap<>();

        String templateJson = "{\"name\": \"John\", \"age\": 30}";
        JsonNode mockJsonNode =  mock(JsonNode.class);
        when(mockFormatter.format(any(Map.class))).thenReturn(templateJson);
        when(mockFormatter.getSelectiveDisclosureInfo(mockTemplateName))
                .thenReturn(Arrays.asList("$.name"));
        when(objectMapper.readTree(templateJson)).thenReturn(mockJsonNode);

        String result = sdjwt.createCredential(templateParams, mockTemplateName);

        assertNotNull(result);
        assertTrue(result.contains("~"));
    }

    @Test
    public void testCreateCredential_WithInvalidJson_ReturnsFallbackJwt() throws JsonProcessingException {
        String mockTemplateName = "badTemplate";
        Map<String, Object> templateParams = new HashMap<>();

        when(mockFormatter.format(any(Map.class))).thenReturn("{invalid json}");
        when(mockFormatter.getSelectiveDisclosureInfo(mockTemplateName)).thenReturn(Arrays.asList("$.invalid"));

        CertifyException exception = assertThrows(CertifyException.class, () -> {
            sdjwt.createCredential(templateParams, mockTemplateName);
        });

        assertEquals("SD_PATH_VALIDATION_FAILED", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("One or more SD paths are not present in the templated JSON"));
    }

    @Test
    public void testCreateCredential_WithValidSDPathsButInvalidJsonParsing_ThrowsJsonProcessingError() throws JsonProcessingException {
        String mockTemplateName = "validSDPathsButBadJson";
        Map<String, Object> templateParams = new HashMap<>();

        String templateJson = "{\"name\": \"John\"}"; // Valid JSON for SD path validation
        when(mockFormatter.format(any(Map.class))).thenReturn(templateJson);
        when(mockFormatter.getSelectiveDisclosureInfo(mockTemplateName))
                .thenReturn(Arrays.asList("$.name")); // Valid SD path
        when(objectMapper.readTree(templateJson)).thenThrow(new JsonProcessingException("Invalid JSON for ObjectMapper") {});

        CertifyException exception = assertThrows(CertifyException.class, () -> {
            sdjwt.createCredential(templateParams, mockTemplateName);
        });

        assertEquals("JSON_PROCESSING_ERROR", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Error processing JSON for SDJWT creation"));
    }

    @Test
    public void testAddProof_ShouldReplaceUnsignedHeaderWithSignedJWT() {
        String unsignedVC = "header.payload~disclosure";
        String signedJwt = "signed.header.payload";

        JWTSignatureResponseDto signedResponse = new JWTSignatureResponseDto();
        signedResponse.setJwtSignedData(signedJwt);

        when(mockSignatureService.jwsSignV2(any(JWSSignatureRequestDtoV2.class))).thenReturn(signedResponse);

        VCResult<?> result = sdjwt.addProof(unsignedVC, null, "RS256", "appID", "refID", "url", "Ed25519Signature2020");

        assertNotNull(result);
        assertTrue(((String) result.getCredential()).startsWith("signed.header.payload"));
    }

    @Test
    public void testAddProof_ShouldSendCorrectSignatureRequest() {
        String unsignedVC = "header.payload~disclosure";

        JWTSignatureResponseDto response = new JWTSignatureResponseDto();
        response.setJwtSignedData("signed.jwt");
        when(mockSignatureService.jwsSignV2(any(JWSSignatureRequestDtoV2.class))).thenReturn(response);

        sdjwt.addProof(unsignedVC, null, "PS256", "myApp", "myRef", "https://example.com", "Ed25519Signature2020");

        verify(mockSignatureService).jwsSignV2(argThat(dto ->
                "myApp".equals(dto.getApplicationId()) &&
                        "myRef".equals(dto.getReferenceId()) &&
                        "PS256".equals(dto.getSignAlgorithm()) &&
                        dto.getIncludePayload() &&
                        dto.getIncludeCertificateChain() &&
                        "".equals(dto.getCertificateUrl())
        ));
    }

    // Unit tests for validateSDPaths method

    @Test
    public void validateSDPaths_WithNullPaths_ReturnsTrue() throws Exception {
        String templatedJSON = "{\"name\": \"John\", \"age\": 30}";

        boolean result = callValidateSDPaths(templatedJSON, null);

        assertTrue(result);
    }

    @Test
    public void validateSDPaths_WithEmptyPaths_ReturnsTrue() throws Exception {
        String templatedJSON = "{\"name\": \"John\", \"age\": 30}";
        List<String> emptyPaths = new ArrayList<>();

        boolean result = callValidateSDPaths(templatedJSON, emptyPaths);

        assertTrue(result);
    }

    @Test
    public void validateSDPaths_WithValidSimplePaths_ReturnsTrue() throws Exception {
        String templatedJSON = "{\"credentialSubject\": {\"dateOfBirth\": \"1990-01-01\"}, \"region\": \"US\"}";
        List<String> sdPaths = Arrays.asList("$.credentialSubject.dateOfBirth", "$.region");

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertTrue(result);
    }

    @Test
    public void validateSDPaths_WithInvalidPath_ReturnsFalse() throws Exception {
        String templatedJSON = "{\"credentialSubject\": {\"dateOfBirth\": \"1990-01-01\"}}";
        List<String> sdPaths = Arrays.asList("$.credentialSubject.invalidField");

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertFalse(result);
    }

    @Test
    public void validateSDPaths_WithValidWildcardPaths_ReturnsTrue() throws Exception {
        String templatedJSON = "{\"identityDetails\": {\"firstName\": \"John\", \"lastName\": \"Doe\"}}";
        List<String> sdPaths = Arrays.asList("$.identityDetails.*");

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertTrue(result);
    }

    @Test
    public void validateSDPaths_WithWildcardPathOnEmptyObject_ReturnsFalse() throws Exception {
        String templatedJSON = "{\"identityDetails\": {}}";
        List<String> sdPaths = Arrays.asList("$.identityDetails.*");

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertFalse(result);
    }

    @Test
    public void validateSDPaths_WithValidArrayPaths_ReturnsTrue() throws Exception {
        String templatedJSON = "{\"fullName\": [{\"value\": \"John\"}, {\"value\": \"Doe\"}]}";
        List<String> sdPaths = Arrays.asList("$.fullName[*].value");

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertTrue(result);
    }

    @Test
    public void validateSDPaths_WithArrayPathOnEmptyArray_ReturnsFalse() throws Exception {
        String templatedJSON = "{\"fullName\": []}";
        List<String> sdPaths = Arrays.asList("$.fullName[*].value");

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertFalse(result);
    }

    @Test
    public void validateSDPaths_WithComplexNestedArrayPaths_ReturnsTrue() throws Exception {
        String templatedJSON = "{\"gender\": [{\"type\": \"M\", \"details\": {\"code\": \"MALE\"}}]}";
        List<String> sdPaths = Arrays.asList("$.gender[*].*");

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertTrue(result);
    }

    @Test
    public void validateSDPaths_WithMixedValidAndInvalidPaths_ReturnsFalse() throws Exception {
        String templatedJSON = "{\"credentialSubject\": {\"dateOfBirth\": \"1990-01-01\"}, \"region\": \"US\"}";
        List<String> sdPaths = Arrays.asList(
            "$.credentialSubject.dateOfBirth",
            "$.region",
            "$.nonExistentField"
        );

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertFalse(result);
    }

    @Test
    public void validateSDPaths_WithAllValidComplexPaths_ReturnsTrue() throws Exception {
        String templatedJSON = "{\n" +
            "  \"credentialSubject\": {\n" +
            "    \"dateOfBirth\": \"1990-01-01\"\n" +
            "  },\n" +
            "  \"contactDetails\": {\n" +
            "    \"email\": \"john@example.com\"\n" +
            "  },\n" +
            "  \"identityDetails\": {\n" +
            "    \"firstName\": \"John\",\n" +
            "    \"lastName\": \"Doe\"\n" +
            "  },\n" +
            "  \"gender\": [{\n" +
            "    \"type\": \"M\",\n" +
            "    \"details\": {\n" +
            "      \"code\": \"MALE\"\n" +
            "    }\n" +
            "  }],\n" +
            "  \"fullName\": [{\n" +
            "    \"value\": \"John Doe\"\n" +
            "  }],\n" +
            "  \"region\": \"US\"\n" +
            "}";

        List<String> sdPaths = Arrays.asList(
            "$.credentialSubject.dateOfBirth",
            "$.contactDetails",
            "$.identityDetails.*",
            "$.gender[*].*",
            "$.fullName[*].value",
            "$.region"
        );

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertTrue(result);
    }

    @Test
    public void validateSDPaths_WithInvalidJSON_ReturnsFalse() throws Exception {
        String invalidJSON = "{invalid json}";
        List<String> sdPaths = Arrays.asList("$.credentialSubject.dateOfBirth");

        boolean result = callValidateSDPaths(invalidJSON, sdPaths);

        assertFalse(result);
    }

    @Test
    public void validateSDPaths_WithNullJSON_ReturnsFalse() throws Exception {
        List<String> sdPaths = Arrays.asList("$.credentialSubject.dateOfBirth");

        boolean result = callValidateSDPaths(null, sdPaths);

        assertFalse(result);
    }

    @Test
    public void validateSDPaths_WithEmptyJSON_ReturnsFalse() throws Exception {
        String emptyJSON = "{}";
        List<String> sdPaths = Arrays.asList("$.credentialSubject.dateOfBirth");

        boolean result = callValidateSDPaths(emptyJSON, sdPaths);

        assertFalse(result);
    }

    @Test
    public void validateSDPaths_WithNestedObjectPath_ReturnsTrue() throws Exception {
        String templatedJSON = "{\"level1\": {\"level2\": {\"level3\": \"value\"}}}";
        List<String> sdPaths = Arrays.asList("$.level1.level2.level3");

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertTrue(result);
    }

    @Test
    public void validateSDPaths_WithArrayIndexPath_ReturnsTrue() throws Exception {
        String templatedJSON = "{\"items\": [\"first\", \"second\", \"third\"]}";
        List<String> sdPaths = Arrays.asList("$.items[0]", "$.items[1]");

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertTrue(result);
    }

    @Test
    public void validateSDPaths_WithOutOfBoundsArrayIndex_ReturnsFalse() throws Exception {
        String templatedJSON = "{\"items\": [\"first\", \"second\"]}";
        List<String> sdPaths = Arrays.asList("$.items[5]");

        boolean result = callValidateSDPaths(templatedJSON, sdPaths);

        assertFalse(result);
    }

}
