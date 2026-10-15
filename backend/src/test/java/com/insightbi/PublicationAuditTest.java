package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** P2-02: narrow publication audit, not a multi-user authorization implementation. */
class PublicationAuditTest {
  private JdbcTemplate db;
  private BiApi api;

  @BeforeEach void setup(){
    String url="jdbc:h2:mem:publication_audit_"+UUID.randomUUID()+
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    db=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    api=new BiApi(store,db,"test-secret-at-least-12-chars");
  }

  @Test void publishUnpublishRestoreAreAuditedAndIdempotentNoOpsAreSkipped(){
    String id=String.valueOf(api.create(Map.of("name","经营审计","mode","grid")).get("id"));
    assertEquals(List.of(),api.publicationAudit(id));
    api.unpublish(id);
    assertTrue(api.publicationAudit(id).isEmpty(),"draft no-op is not an event");

    var released=api.publish(id);
    assertEquals(1,released.get("version"));
    api.unpublish(id);
    api.unpublish(id);
    var restored=api.restore(id,1,Map.of("revision",1));
    assertEquals(2,restored.get("revision"));

    var events=api.publicationAudit(id);
    assertEquals(3,events.size());
    assertEquals(1,events.stream().filter(e->"PUBLISH".equals(e.get("event_type"))).count());
    assertEquals(1,events.stream().filter(e->"UNPUBLISH".equals(e.get("event_type"))).count());
    assertEquals(1,events.stream().filter(e->"RESTORE".equals(e.get("event_type"))).count());
    for(var event:events){
      assertEquals("admin",event.get("actor"));
      assertNotNull(event.get("created_at"));
      assertEquals(Set.of("event_type","actor","version_no","created_at"),event.keySet());
    }
    assertTrue(events.stream().anyMatch(e->"RESTORE".equals(e.get("event_type"))&&
      Integer.valueOf(1).equals(e.get("version_no"))));

    assertThrows(IllegalStateException.class,()->
      api.restore(id,1,Map.of("revision",1)),"outdated revision is not restorable");
    assertEquals(3,api.publicationAudit(id).size(),"failed action writes no audit row");
  }

  @Test void unknownReportsCannotExposeOtherPublicationAuditEvents(){
    String id=String.valueOf(api.create(Map.of("name","内部主题","mode","grid")).get("id"));
    api.publish(id);
    assertThrows(NoSuchElementException.class,()->api.publicationAudit("missing"));
    assertEquals(1,api.publicationAudit(id).size());
    assertEquals(1,db.queryForObject(
      "SELECT COUNT(*) FROM insight_publication_audit WHERE dashboard_id=?",Integer.class,id));
  }

  @Test void optInApprovalRejectsUnreviewedOrStaleRevisionsAndPreventsReplay(){
    String id=String.valueOf(api.create(Map.of("name","审核报表","kind","analysis","mode","grid")).get("id"));
    assertEquals(false,api.approval(id).get("required"));
    assertEquals(true,api.enableApproval(id).get("required"));
    api.enableApproval(id);
    assertThrows(IllegalStateException.class,()->api.publish(id),
      "opted-in analysis cannot bypass review");
    assertThrows(IllegalStateException.class,()->api.reviewApproval(id,Map.of("approved",true)));
    assertEquals("PENDING",api.submitApproval(id,Map.of()).get("state"));
    assertThrows(IllegalStateException.class,()->api.submitApproval(id,Map.of()));
    assertEquals("REJECTED",api.reviewApproval(id,
      Map.of("approved",false,"comment","指标需要核验")).get("state"));
    assertThrows(IllegalStateException.class,()->api.publish(id));
    api.submitApproval(id,Map.of());
    assertEquals("APPROVED",api.reviewApproval(id,
      Map.of("approved",true,"comment","通过")).get("state"));
    // Any save after approval changes the server-side revision: approval expires.
    var current=api.get(id,false);
    api.update(id,Map.of("name","审核报表 V2","mode","grid","revision",1,
      "content",current.get("content")));
    assertThrows(IllegalStateException.class,()->api.publish(id),
      "approved revision 1 must not authorize revision 2");
    assertThrows(IllegalStateException.class,()->
      api.reviewApproval(id,Map.of("approved",true)),
      "prior approved request must not be reviewed twice");
    api.submitApproval(id,Map.of());
    api.reviewApproval(id,Map.of("approved",true));
    assertEquals(1,api.publish(id).get("version"));
    assertEquals("CONSUMED",api.approval(id).get("state"));
    assertThrows(IllegalStateException.class,()->api.publish(id),
      "approved revision must be single-use");
    api.unpublish(id);
    assertThrows(IllegalStateException.class,()->api.publish(id));
    api.submitApproval(id,Map.of());
    api.reviewApproval(id,Map.of("approved",true));
    assertEquals(2,api.publish(id).get("version"));
    assertEquals(2,db.queryForObject(
      "SELECT COUNT(*) FROM insight_dashboard_version WHERE dashboard_id=?",
      Integer.class,id));
    assertEquals(2,api.publicationAudit(id).stream()
      .filter(e->"PUBLISH".equals(e.get("event_type"))).count());
    assertEquals(1,api.publicationAudit(id).stream()
      .filter(e->"ENABLE_APPROVAL".equals(e.get("event_type"))).count());
    assertEquals(1,api.publicationAudit(id).stream()
      .filter(e->"REJECT".equals(e.get("event_type"))).count());
    assertEquals(3,api.publicationAudit(id).stream()
      .filter(e->"APPROVE".equals(e.get("event_type"))).count());
  }

  @Test void invalidApprovalPayloadAndRejectedOrChangedSubmissionNeverPublish(){
    String id=String.valueOf(api.create(Map.of("name","预算审核","kind","analysis","mode","grid")).get("id"));
    api.enableApproval(id);
    assertThrows(IllegalArgumentException.class,()->
      api.submitApproval(id,Map.of("approved",true)));
    api.submitApproval(id,Map.of());
    assertThrows(IllegalArgumentException.class,()->
      api.reviewApproval(id,Map.of("approved",true,"role","ADMIN")));
    assertThrows(IllegalArgumentException.class,()->
      api.reviewApproval(id,Map.of("approved","true")));
    var current=api.get(id,false);
    api.update(id,Map.of("name","预算 V2","mode","grid","revision",1,
      "content",current.get("content")));
    assertThrows(IllegalStateException.class,()->
      api.reviewApproval(id,Map.of("approved",true)));
    assertThrows(IllegalStateException.class,()->api.publish(id));
    assertEquals(0,db.queryForObject(
      "SELECT COUNT(*) FROM insight_dashboard_version WHERE dashboard_id=?",Integer.class,id));
    api.submitApproval(id,Map.of());
    api.reviewApproval(id,Map.of("approved",false));
    assertThrows(IllegalStateException.class,()->api.publish(id));
    assertEquals(0,db.queryForObject(
      "SELECT COUNT(*) FROM insight_dashboard_version WHERE dashboard_id=?",Integer.class,id));
  }
}
