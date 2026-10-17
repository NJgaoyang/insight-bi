package com.insightbi.analysis;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;

/**
 * Per-connection immutable-from-the-source CA snapshot. Connector/J reads the
 * private snapshot, NEVER the operator's concurrently replaceable CA path.
 *
 * POSIX-only, fail closed elsewhere. Owner/OS-level filesystem compromise is
 * explicitly outside this protection; it is not a TLS handshake test.
 */
final class SemanticRelationRlsTrustSnapshot implements AutoCloseable {
  private static final int MAX_BYTES=2*1024*1024;
  private final Path directory;
  private final Path file;
  private boolean closed;

  private SemanticRelationRlsTrustSnapshot(Path directory,Path file){
    this.directory=directory;
    this.file=file;
  }

  static SemanticRelationRlsTrustSnapshot capture(Path source,char[] password,
                                                  String expectedDigest)
      throws IOException,GeneralSecurityException {
    if(source==null||password==null||password.length==0||
       expectedDigest==null||!expectedDigest.matches("[0-9a-f]{64}"))
      throw new IllegalStateException("来源 CA 或部署的固定证书指纹无效");
    if(Files.isSymbolicLink(source))
      throw new IllegalStateException("不得读取可重定向的 CA 信任库路径");
    var attributes=Files.readAttributes(source,
      java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
    if(!attributes.isRegularFile()||attributes.size()<1||
       attributes.size()>MAX_BYTES)
      throw new IllegalStateException("CA 文件不是有效大小的常规信任库");
    byte[] bytes;
    try(var channel=Files.newByteChannel(source,
          Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
        var input=Channels.newInputStream(channel)){
      bytes=input.readNBytes(MAX_BYTES+1);
    }
    try{
      if(bytes.length<1||bytes.length>MAX_BYTES||
         !expectedDigest.equals(
           SemanticRelationRlsTlsConnectionFactory.caFingerprint(
             bytes,password,new Date())))
        throw new IllegalStateException("CA 文件与固定信任锚不同或在读取时被替换");
      Path folder=null;
      Path snapshot=null;
      try{
        // No insecure fallback to globally writable temporary directories.
        folder=Files.createTempDirectory("insightbi-rls-ca-",
          PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
        if(!Files.getPosixFilePermissions(folder).equals(
            PosixFilePermissions.fromString("rwx------")))
          throw new IllegalStateException("私有 CA 快照目录权限不安全");
        snapshot=Files.createTempFile(folder,"ca-", ".p12",
          PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rw-------")));
        try(var stream=Files.newOutputStream(snapshot,
            StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){
          stream.write(bytes);
        }
        Files.setPosixFilePermissions(snapshot,
          PosixFilePermissions.fromString("r--------"));
        if(!Files.getPosixFilePermissions(snapshot).equals(
            PosixFilePermissions.fromString("r--------")))
          throw new IllegalStateException("CA 快照文件权限不安全");
        return new SemanticRelationRlsTrustSnapshot(folder,snapshot);
      }catch(IOException|RuntimeException failure){
        if(snapshot!=null)try{Files.deleteIfExists(snapshot);}
          catch(IOException cleanup){failure.addSuppressed(cleanup);}
        if(folder!=null)try{Files.deleteIfExists(folder);}
          catch(IOException cleanup){failure.addSuppressed(cleanup);}
        throw failure;
      }
    }finally{
      Arrays.fill(bytes,(byte)0);
    }
  }

  String url(){
    if(closed)throw new IllegalStateException("CA 快照已销毁");
    return file.toUri().toString();
  }

  @Override public void close()throws IOException{
    if(closed)return;
    closed=true;
    IOException error=null;
    try{Files.deleteIfExists(file);}catch(IOException failure){error=failure;}
    try{Files.deleteIfExists(directory);}catch(IOException failure){
      if(error==null)error=failure;else error.addSuppressed(failure);
    }
    if(error!=null)throw error;
  }
}
