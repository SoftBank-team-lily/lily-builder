package com.lily.builder.cloud;

import com.lily.jev.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudContextTest {
    CloudPolicy.Request request(CloudPolicy.Context context) {
        var r = CloudPolicyTest.request("balanced");
        return new CloudPolicy.Request(r.provider(),r.priority(),r.profile(),r.maxMonthlyCostUsd(),r.maxP95Ms(),r.regions(),r.capabilities(),context);
    }
    CloudPolicy.Decision decide(CloudPolicy.Context context) {
        return new CloudPolicy(Jev.disabled()).decide(request(context),CloudPolicyTest.candidates(),CloudPolicyTest.WORKERS,CloudPolicyTest.NOW,300);
    }
    @Test void sameCloudDataRequirementOverridesPriceAndModelPreference() {
        var result = decide(new CloudPolicy.Context("gcp",true,null,false,Set.of()));
        assertThat(result.provider()).isEqualTo("gcp");
        assertThat(result.excluded()).contains(new CloudPolicy.Exclusion("aws","data_locality_required"));
    }
    @Test void existingCloudAndServiceRequirementsAreMandatory() {
        assertThat(decide(new CloudPolicy.Context(null,false,"aws",true,Set.of())).provider()).isEqualTo("aws");
        var unavailable = decide(new CloudPolicy.Context(null,false,null,false,Set.of("gcp-bigquery")));
        assertThat(unavailable.status()).isEqualTo("held");
        assertThat(unavailable.excluded()).extracting(CloudPolicy.Exclusion::reason).containsOnly("required_service_unavailable");
    }
    @Test void missingContextAndConflictingRequirementsNeverSilentlyRelax() {
        assertThat(decide(new CloudPolicy.Context(null,true,null,false,Set.of())).reason()).isEqualTo("data_location_required");
        assertThat(decide(new CloudPolicy.Context(null,false,null,true,Set.of())).reason()).isEqualTo("existing_cloud_required");
        assertThat(decide(new CloudPolicy.Context("gcp",true,"aws",true,Set.of())).status()).isEqualTo("held");
        assertThat(decide(new CloudPolicy.Context(null,false,"azure",true,Set.of())).status()).isEqualTo("held");
        assertThat(decide(new CloudPolicy.Context("onprem",true,null,false,Set.of())).status()).isEqualTo("held");
    }
    @Test void contextIsJsonSerializableAndReachesJev() {
        var context = new CloudPolicy.Context("gcp",false,"aws",false,Set.of());
        Jev jev = (state,question) -> {
            assertThat(((CloudPolicy.Request)state.get("requirements")).context()).isEqualTo(context);
            assertThatCode(() -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(state)).doesNotThrowAnyException();
            return Optional.of(new Answer("gcp",null,.9));
        };
        assertThat(new CloudPolicy(jev).decide(request(context),CloudPolicyTest.candidates(),CloudPolicyTest.WORKERS,CloudPolicyTest.NOW,300).source()).isEqualTo("jev");
    }
    @Test void recheckPreservesServiceRequirement() {
        var catalog = mock(CloudCatalog.class);
        var aws = CloudPolicyTest.candidates().getFirst();
        var supported = new CloudPolicy.Candidate(aws.provider(),aws.region(),aws.profile(),aws.monthlyCostUsd(),aws.p95Ms(),true,
            Set.of("container","aws-bedrock"),aws.observedAt(),aws.evidenceId());
        when(catalog.read()).thenReturn(List.of(supported),CloudPolicyTest.candidates());
        var service = new CloudService(catalog,CloudApiTest.props("","http://worker"),new CloudPolicy(Jev.disabled()),
            Clock.fixed(CloudPolicyTest.NOW,ZoneOffset.UTC));
        var r = request(new CloudPolicy.Context(null,false,null,false,Set.of("aws-bedrock")));
        var chosen = service.plan(r);
        assertThat(chosen.provider()).isEqualTo("aws");
        assertThat(service.recheck(r,chosen).reason()).isEqualTo("changed_before_dispatch");
    }
    @Test void serviceHintsAreAdvisoryAndEnterpriseIdentityNeedsReview() {
        Map<String,List<String>> signals = new TreeMap<>();
        CloudRepository.extract("@aws-sdk/client-bedrock-runtime @aws-sdk/client-s3 @google-cloud/bigquery @azure/msal-node",signals);
        var evidence = new CloudRepository.Evidence("a".repeat(40),List.of("package.json"),signals,"unknown","rules",null,List.of());
        assertThat(evidence.serviceHints()).contains("aws-bedrock","aws-s3","gcp-bigquery","entra-integration");
        assertThat(evidence.reviewItems()).contains("review_azure_identity_and_service_integration","confirm_data_location");
        assertThat(evidence.facts()).containsKeys("serviceHints","reviewItems");
        assertThat(decide(null).status()).isEqualTo("selected");
    }
}
