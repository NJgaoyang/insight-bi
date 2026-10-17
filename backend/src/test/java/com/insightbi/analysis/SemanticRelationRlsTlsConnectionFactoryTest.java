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
  private record Fixture(DatabaseStore store,Map<String,Object> config){}
  private Fixture fixture(){
    var data=new DriverManagerDataSource(
      "jdbc:h2:mem:tls_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
    var store=new DatabaseStore(new JdbcTemplate(data),new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    var config=new HashMap<String,Object>();
    config.put("engine","mysql");
    config.put("host","db.isolated.invalid");
    config.put("port",3306);
    config.put("db_name","analytics");
    config.put("username","readonly");
    config.put("credential",store.encrypt("isolated-db-password"));
    return new Fixture(store,config);
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
}
