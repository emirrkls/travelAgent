package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real anonymous loopback HTTP producer; never a production endpoint or datasource. */
class PlacePilotV3CoverageContractTest {
    private static final ObjectMapper M=new ObjectMapper();
    private static HttpServer server;
    private static List<PlacePilotHttpProbeService.ProbeTarget> targets;
    private static PlacePilotHttpProbeService.ProbeSuite suite;
    @BeforeAll static void captureRealHttp() throws Exception {
        targets=java.util.stream.IntStream.range(0,71).mapToObj(i->new PlacePilotHttpProbeService.ProbeTarget(
                new UUID(19,i),"Coverage Cafe "+i,"CAFE",37.3751,27.2678)).toList();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath(); var places=M.createArrayNode();
            for(var t:targets) places.add(M.createObjectNode().put("id",t.placeId().toString()).put("name",t.name())
                    .put("category",t.category()).put("latitude",t.latitude()).put("longitude",t.longitude()));
            com.fasterxml.jackson.databind.JsonNode body;
            if(path.equals("/actuator/health")) body=M.createObjectNode().put("status","UP");
            else if(path.endsWith("/nearby")) {
                var nearby=M.createArrayNode();places.forEach(p->nearby.add(M.createObjectNode().set("place",p)));
                nearby.forEach(p->((ObjectNode)p).put("distanceMeters",0));body=nearby;
            } else if(path.endsWith("/bounds")) body=places;
            else if(path.startsWith("/api/v1/places/")) body=places.get(targets.stream().map(t->t.placeId().toString()).toList().indexOf(path.substring("/api/v1/places/".length())));
            else body=M.createObjectNode().set("content",places);
            byte[] raw=M.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,raw.length);exchange.getResponseBody().write(raw);exchange.close();
        });
        server.start();
        var origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        suite=new PlacePilotHttpProbeService(M).captureAfter(new PlacePilotHttpProbeService.ProbeConfiguration(
                origin,origin.resolve("/actuator"),targets.getFirst().placeId(),1,Duration.ofSeconds(5)),targets);
    }
    @AfterAll static void close(){if(server!=null)server.stop(0);}
    @Test void all71CanonicalIdentitiesAreDerivedFromActualValidResponsesForAllFourSurfaces(){
        assertThat(suite.passed()).isTrue();
        var coverage=suite.verifiedV3Coverage(targets);
        for(String name:List.of("search","map_nearby","map_bounds","place_detail")) {
            assertThat(coverage.path(name).size()).isEqualTo(71);
            assertThat(suite.surface(name+"_coverage").observations()).allSatisfy(o->{
                assertThat(o.checkedPlaceId()).isNotNull();assertThat(o.responseValidation()).isEqualTo("VALID");
            });
        }
    }
    @Test void summaryCountsAndPlannedIdentitiesWithoutObservationsAreNotCoverage(){
        var surfaces=new LinkedHashMap<>(suite.surfaces());
        surfaces.put("search_coverage",new PlacePilotHttpProbeService.SurfaceResult(71,0,10,10,true,List.of()));
        assertThatThrownBy(()->PlacePilotHttpProbeService.ProbeSuite.from(surfaces).verifiedV3Coverage(targets)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","DUPLICATE","WRONG_ID","TIMEOUT","HTTP_503","INVALID_SEMANTICS","DEADLINE","NO_ID"})
    void failedOrIncompleteObservationsCannotProduceSuccessfulCoverage(String fault){
        var observations=new ArrayList<>(suite.surface("search_coverage").observations());
        var o=observations.getLast();
        if(fault.equals("MISSING")) observations.removeLast();
        else observations.set(70,new PlacePilotHttpProbeService.ProbeDiagnostic(o.surface(),o.sampleIndex(),o.method(),o.pathTemplate(),
            o.startedAt(),fault.equals("DEADLINE")?5000:o.durationMs(),fault.equals("HTTP_503")?503:200,
            fault.equals("TIMEOUT"),o.transportResult(),fault.equals("INVALID_SEMANTICS")?"INVALID":"VALID",null,
            fault.equals("NO_ID")?null:fault.equals("DUPLICATE")?observations.getFirst().checkedPlaceId():fault.equals("WRONG_ID")?new UUID(29,99):o.checkedPlaceId()));
        var surfaces=new LinkedHashMap<>(suite.surfaces());surfaces.put("search_coverage",PlacePilotHttpProbeService.SurfaceResult.from(observations));
        assertThatThrownBy(()->PlacePilotHttpProbeService.ProbeSuite.from(surfaces).verifiedV3Coverage(targets)).isInstanceOf(IllegalArgumentException.class);
    }
}
