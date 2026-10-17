package com.insightbi.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class SemanticRelationRlsTlsConnectionFactoryTest {
  @TempDir Path directory;
  private static final char[] SECRET="isolated-test-ca".toCharArray();

  // Public, throwaway CA test certificate generated for fixture use; there is
  // no private key or trusted production certificate in this test.
  private static final String CA="""
    -----BEGIN CERTIFICATE-----
    MIIDTzCCAjegAwIBAgIUfLe2bTzfTQEHNXxmfiUNzXi8G+QwDQYJKoZIhvcNAQEL
    BQAwNzEbMBkGA1UEAwwSaXNvbGF0ZWQtdGVzdC1yb290MRgwFgYDVQQKDA9JbnNp
    Z2h0IEJJIFRlc3QwHhcNMjYxMDE3MTAzMjMxWhcNMzYxMDE0MTAzMjMxWjA3MRsw
    GQYDVQQDDBJpc29sYXRlZC10ZXN0LXJvb3QxGDAWBgNVBAoMD0luc2lnaHQgQkkg
    VGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKzr03Hg7doBZlpW
    VhVcxwhRzTPxe+LUOUGO/IaahuN7RCw/1n/okINh2HcgP9Tn+RBQNo5qym6a/c2m
    qXasBRiA5sqI17ATWfxPn6pN7GG6oZiOf4nFdIU2alCR07N02pIsIS7VJjZw8RL8
    Z7GjK3aM2KXUOO6Ljfs5k5tQ6lBI0ptbI7aoXce65Rf5cZq22pyGR7JGxKDwKoO0
    GCXUWfi4bzHbqFLHXjkD8f6mYcyMKvXv8ZrjYqCggJ+L8jGORoe5yhCjkZDc2NZt
    AQLZQ7Mj4rGEDEpALVzs1QTtZALe85P5N4jsWgxr7f1Cs92xDfwskx194k8hq5+q
    +wjbfRsCAwEAAaNTMFEwHQYDVR0OBBYEFL4VwrosBbZw3R4mxyapg5JSvExlMB8G
    A1UdIwQYMBaAFL4VwrosBbZw3R4mxyapg5JSvExlMA8GA1UdEwEB/wQFMAMBAf8w
    DQYJKoZIhvcNAQELBQADggEBADAKuaHM/3rcN+nz2AuVAUrDlkhxWaSTFAEsxF9D
    ZMkTmovponLYv1jQwy0K7bCFJ5zW8EED5JnTPntF8EqdXugK5BTEdXF8jc5GsyaD
    IK9k9pkx48Enoa+1cScsNwzuciEEcxiR1mdc2ZaYkOIKr9kBjsuiqIWv1bugqTtA
    JgRUbrt21NwPD8D/KPjeiVovrYp0SU1t0wZHwdpNJU+flYu1DufZTFPHd6CpokTo
    baf+tPZvASZN8FEbEdZC8M86kbYx1MYJGXXH+YH1PIXvES10tU/rb3cp0mtYhFiH
    fsJM1xWiBo3Nc5PJPejWIWf8wsJqSVzfBxWdvAXihZc2UE0=
    -----END CERTIFICATE-----
    """;

  private static final String ROTATED_CA="""
    -----BEGIN CERTIFICATE-----
    MIIDdTCCAl2gAwIBAgIUXKoRW/oClGc1Eedw5S3nu8zfrHQwDQYJKoZIhvcNAQEL
    BQAwQjEjMCEGA1UEAwwaaXNvbGF0ZWQtcm90YXRlZC10ZXN0LXJvb3QxGzAZBgNV
    BAoMEkluc2lnaHQgQkkgRml4dHVyZTAeFw0yNjEwMTcxMDQ3MDFaFw0zNjEwMTQx
    MDQ3MDFaMEIxIzAhBgNVBAMMGmlzb2xhdGVkLXJvdGF0ZWQtdGVzdC1yb290MRsw
    GQYDVQQKDBJJbnNpZ2h0IEJJIEZpeHR1cmUwggEiMA0GCSqGSIb3DQEBAQUAA4IB
    DwAwggEKAoIBAQC55WtV2OZh3wNrCVppmJlcmnkDugCojBHDAK4jNwjbN3pLFkXk
    6X6dUZV1lzPakr/JZp2Rfxm5tAMGF0z3DECvZzNr34oKXLrLu6c0VVqPk0361Hgt
    1fcWoeWkVlFrIJA9X+6LoGuL+T8RFqAOXe0BdX9vsZ4k2eJj+u9qgBW4GCaja1O8
    DpwTcRWmJU1hAxekLKMj72OZx4CTzyKha4EQepv0BrPdp0dwJ6o+pyK+1h+LUtoM
    oDXlbVpDQ8Rntk2Y+7uYd2J0SgSLleEP5fdKioJx+qhgZXxrR58TX8kB+HBrDmcY
    CjwacSmQAQay0EwrNz2yTq7/CtxbGMBM897tAgMBAAGjYzBhMB0GA1UdDgQWBBSG
    ZYhrIZLQdDJGm4v1i+v73fLZRjAfBgNVHSMEGDAWgBSGZYhrIZLQdDJGm4v1i+v7
    3fLZRjAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjANBgkqhkiG9w0B
    AQsFAAOCAQEAPbtpqjwB5lzkcMZ5cX+1sxWYdKIBe/iLzL9Jq0CXcfozllqCRRRB
    yoMJiLnVjiixjiqXPP0PRTF7UzLF4F74iRqLnAiyIR13fBaSzp04WlNp6EzMkzYD
    HM0y5rvb3MQljCB+skVuEG20EkmdO9ry88O826aY91D786x2BMpNCbsATxQtckA6
    I3CcZeBLKuVfX4rHG2FxMf+PLh0joMYF5J4xCiwBpEduL5+N+mswb7NlZ1bUC/Ns
    0Mc/TJ+0HqTr8CVpk4XQtp+B9oxJ+fwO3X+GuxJfmtrFxtx0nUKOviRwFNHRYFF6
    aEqpNudEOmxyozNwBMDSLKIoOcJBBNC0LA==
    -----END CERTIFICATE-----
    """;

  private Path rotatedRoot()throws Exception{
    var root=CertificateFactory.getInstance("X.509").generateCertificate(
      new ByteArrayInputStream(ROTATED_CA.getBytes(StandardCharsets.UTF_8)));
    var ks=KeyStore.getInstance("PKCS12");
    ks.load(null,SECRET);
    ks.setCertificateEntry("new-approved-root",root);
    var file=directory.resolve("rotated-ca.p12");
    try(var out=Files.newOutputStream(file)){ks.store(out,SECRET);}
    return file;
  }

  private Path trustedRoot()throws Exception{
    var root=CertificateFactory.getInstance("X.509").generateCertificate(
      new ByteArrayInputStream(CA.getBytes(StandardCharsets.UTF_8)));
    var ks=KeyStore.getInstance("PKCS12");
    ks.load(null,SECRET);
    ks.setCertificateEntry("isolated-root",root);
    var file=directory.resolve("isolated-trusted-ca.p12");
    try(var out=Files.newOutputStream(file)){ks.store(out,SECRET);}
    return file;
  }
  private record Fixture(DatabaseStore store,JdbcTemplate db,
                         DataSourceTransactionManager transactions,
                         Map<String,Object> config){}
  private Fixture fixture(){
    var data=new DriverManagerDataSource(
      "jdbc:h2:mem:tls_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
    var db=new JdbcTemplate(data);
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    var config=new HashMap<String,Object>();
    config.put("engine","mysql");
    config.put("host","db.isolated.invalid");
    config.put("port",3306);
    config.put("db_name","analytics");
    config.put("username","readonly");
    config.put("credential",store.encrypt("isolated-db-password"));
    db.update("INSERT INTO insight_connection"+
      "(id,name,engine,host,port,db_name,username,credential)"+
      " VALUES(?,?,?,?,?,?,?,?)",
      "approved-conn","Pinned","mysql","db.isolated.invalid",3306,
      "analytics","readonly",config.get("credential"));
    return new Fixture(store,db,new DataSourceTransactionManager(data),config);
  }

  @Test void secureConfigRequiresIdentityVerificationAndKeepsSecretsOutOfUrl()
      throws Exception{
    var f=fixture();
    var settings=SemanticRelationRlsTlsConnectionFactory.prepare(
      f.config(),f.store(),trustedRoot(),SECRET);
    var flags=settings.properties();
    assertTrue(settings.url().contains("sslMode=VERIFY_IDENTITY"));
    assertEquals("VERIFY_IDENTITY",flags.getProperty("sslMode"));
    assertEquals("false",flags.getProperty("fallbackToSystemTrustStore"));
    assertEquals("false",flags.getProperty("allowPublicKeyRetrieval"));
    assertEquals("PKCS12",flags.getProperty("trustCertificateKeyStoreType"));
    assertTrue(flags.getProperty("trustCertificateKeyStoreUrl").startsWith("file:"));
    assertEquals("readonly",flags.getProperty("user"));
    assertEquals("isolated-db-password",flags.getProperty("password"));
    for(String leak:List.of("isolated-db-password",f.config().get("credential").toString(),
        new String(SECRET))){
      assertFalse(settings.url().contains(leak));
      assertFalse(settings.toString().contains(leak));
    }
    assertFalse(settings.url().contains("useSSL"));
    assertDoesNotThrow(()->SemanticRelationRlsTlsConnectionFactory
      .requireVerifiedUrl(settings.url()));
    flags.setProperty("sslMode","DISABLED");
    assertEquals("VERIFY_IDENTITY",settings.properties().getProperty("sslMode"));
  }

  @Test void rejectsMissingInvalidOrSymlinkedTrustStoreAndNonCaCertificate()
      throws Exception{
    var f=fixture();
    var trust=trustedRoot();
    assertThrows(RuntimeException.class,()->
      SemanticRelationRlsTlsConnectionFactory.prepare(
        f.config(),f.store(),directory.resolve("missing.p12"),SECRET));
    assertThrows(Exception.class,()->
      SemanticRelationRlsTlsConnectionFactory.prepare(
        f.config(),f.store(),trust,"wrong-password".toCharArray()));
    var link=directory.resolve("indirect.p12");
    Files.createSymbolicLink(link,trust.getFileName());
    assertThrows(RuntimeException.class,()->
      SemanticRelationRlsTlsConnectionFactory.prepare(
        f.config(),f.store(),link,SECRET));
    var empty=KeyStore.getInstance("PKCS12");
    empty.load(null,SECRET);
    var emptyFile=directory.resolve("empty.p12");
    try(var out=Files.newOutputStream(emptyFile)){empty.store(out,SECRET);}
    assertThrows(IllegalStateException.class,()->
      SemanticRelationRlsTlsConnectionFactory.prepare(
        f.config(),f.store(),emptyFile,SECRET));
    var changed=new HashMap<>(f.config());
    changed.put("host","evil.invalid?useSSL=false");
    assertThrows(IllegalStateException.class,()->
      SemanticRelationRlsTlsConnectionFactory.prepare(
        changed,f.store(),trust,SECRET));
    changed.put("host","db.isolated.invalid");
    changed.put("engine","starrocks");
    assertThrows(IllegalStateException.class,()->
      SemanticRelationRlsTlsConnectionFactory.prepare(
        changed,f.store(),trust,SECRET));
  }

  @Test void urlPolicyRejectsDowngradesDuplicatesAndUnexpectedParameters()
      throws Exception{
    var f=fixture();
    String safe=SemanticRelationRlsTlsConnectionFactory.prepare(
      f.config(),f.store(),trustedRoot(),SECRET).url();
    for(String invalid:List.of(
      safe.replace("VERIFY_IDENTITY","VERIFY_CA"),
      safe.replace("VERIFY_IDENTITY","REQUIRED"),
      safe.replace("VERIFY_IDENTITY","PREFERRED"),
      safe.replace("VERIFY_IDENTITY","DISABLED"),
      safe.replace("sslMode=VERIFY_IDENTITY&",""),
      safe+"&useSSL=false",
      safe+"&verifyServerCertificate=false",
      safe+"&sslMode=DISABLED",
      safe+"&allowPublicKeyRetrieval=true",
      safe.replace("allowPublicKeyRetrieval=false","allowPublicKeyRetrieval=true"),
      safe.replace("fallbackToSystemTrustStore=false","fallbackToSystemTrustStore=true"),
      safe.replace("socketTimeout=5000","socketTimeout=0"),
      safe+"#spoof",safe.replace("jdbc:mysql://","jdbc:mysql:replication://")))
      assertThrows(IllegalStateException.class,()->
        SemanticRelationRlsTlsConnectionFactory.requireVerifiedUrl(invalid),invalid);
  }

  private static SemanticRelationRlsApprovedSourceGate.VerifiedSource approved(Fixture f){
    return new SemanticRelationRlsApprovedSourceGate.VerifiedSource(
      "mysql","analytics","db.isolated.invalid",3306,"readonly","approved-conn",
      SemanticRelationRlsPlanSnapshot.hash((String)f.config().get("credential")));
  }

  private SemanticRelationRlsTlsConnectionFactory.Settings pinned(Fixture f,
      SemanticRelationRlsApprovedSourceGate.VerifiedSource approved,
      Path storeFile,String expectedCaDigest){
    var tx=new TransactionTemplate(f.transactions());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return tx.execute(state->{
      try{
        return SemanticRelationRlsTlsConnectionFactory.prepareApproved(
          f.db(),f.store(),approved,storeFile,SECRET,expectedCaDigest);
      }catch(Exception checked){
        throw new IllegalStateException("测试用的已审批 TLS 设置未通过安全检查",checked);
      }
    });
  }

  @Test void boundSourceCredentialAndEndpointMustMatchLockedApprovedRow()throws Exception{
    var f=fixture();
    var root=trustedRoot();
    String ca=SemanticRelationRlsTlsConnectionFactory.caFingerprint(root,SECRET);
    var verified=approved(f);
    var settings=pinned(f,verified,root,ca);
    assertEquals("readonly",settings.properties().getProperty("user"));
    assertEquals("isolated-db-password",settings.properties().getProperty("password"));
    assertFalse(settings.toString().contains(verified.credentialHash()));
    // A changed encrypted credential invalidates the existing approval
    // even when host, user and other metadata stay identical.
    f.db().update("UPDATE insight_connection SET credential=? WHERE id=?",
      f.store().encrypt("new-password"),"approved-conn");
    assertThrows(IllegalStateException.class,()->pinned(f,verified,root,ca));
    f.db().update("UPDATE insight_connection SET credential=? WHERE id=?",
      f.config().get("credential"),"approved-conn");
    f.db().update("UPDATE insight_connection SET username=? WHERE id=?",
      "other-user","approved-conn");
    assertThrows(IllegalStateException.class,()->pinned(f,verified,root,ca));
    f.db().update("UPDATE insight_connection SET username=? WHERE id=?",
      "readonly","approved-conn");
    f.db().update("UPDATE insight_connection SET host=? WHERE id=?",
      "other.invalid","approved-conn");
    assertThrows(IllegalStateException.class,()->pinned(f,verified,root,ca));
  }

  @Test void rotatedCaCannotSilentlyReplaceTrustAnchorAndExpiryFailsClosed()
      throws Exception{
    var f=fixture();
    var root=trustedRoot();
    String previous=SemanticRelationRlsTlsConnectionFactory.caFingerprint(root,SECRET);
    var verified=approved(f);
    var newRoot=rotatedRoot();
    String rotated=SemanticRelationRlsTlsConnectionFactory.caFingerprint(newRoot,SECRET);
    assertNotEquals(previous,rotated);
    assertThrows(IllegalStateException.class,()->pinned(f,verified,newRoot,previous));
    // A trusted operator must explicitly update the deployment's expected CA.
    assertEquals("readonly",pinned(f,verified,newRoot,rotated)
      .properties().getProperty("user"));
    assertThrows(java.security.cert.CertificateExpiredException.class,()->
      SemanticRelationRlsTlsConnectionFactory.caFingerprint(root,SECRET,
        java.util.Date.from(java.time.Instant.parse("2041-01-01T00:00:00Z"))));
    assertThrows(IllegalStateException.class,()->pinned(f,verified,root,"f".repeat(64)));
    assertThrows(IllegalStateException.class,()->pinned(f,verified,root,"bad-pin"));
  }

  @Test void pinnedPreparationFailsClosedOutsideApprovedMetadataTransaction()throws Exception{
    var f=fixture();
    var caFile=trustedRoot();
    String ca=SemanticRelationRlsTlsConnectionFactory.caFingerprint(caFile,SECRET);
    assertThrows(IllegalStateException.class,()->
      SemanticRelationRlsTlsConnectionFactory.prepareApproved(
        f.db(),f.store(),approved(f),caFile,SECRET,ca));
    assertThrows(IllegalStateException.class,()->
      pinned(f,approved(f),caFile,null));
  }
}
