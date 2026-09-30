package com.planify;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Component
public class FileStorage {
    public static final long MAX_SIZE = 20L * 1024 * 1024;
    private final Path root;
    public FileStorage(@Value("${planify.upload-dir}") String directory) throws IOException {
        root = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
    }
    public record Stored(String key, String name) {}
    public Stored replace(MultipartFile file, String oldKey) {
        if (file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank())
            throw new RuleException("파일 1개를 선택해주세요.");
        if (file.isEmpty()) throw new RuleException("선택한 파일이 0바이트입니다. 내용을 저장한 뒤 다시 제출해주세요.");
        if (file.getSize() > MAX_SIZE) throw new RuleException("파일은 최대 20MB까지 업로드할 수 있습니다.");
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String name = original.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank() || name.length() > 200) throw new RuleException("파일 이름은 1~200자로 입력해주세요.");
        String key = UUID.randomUUID().toString();
        try (var input = file.getInputStream()) { Files.copy(input, path(key)); }
        catch (IOException ex) { delete(key); throw new RuleException("파일 저장에 실패했습니다. 다시 시도해주세요."); }
        // Keep the old file until the database commit succeeds; remove the new one on rollback.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) delete(oldKey); else delete(key);
            }
        });
        return new Stored(key, name);
    }
    public void removeAfterCommit(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { delete(key); }
        });
    }
    private Path path(String key) {
        if (key == null || !key.matches("[0-9a-f-]{36}")) throw new RuleException("파일을 찾을 수 없습니다.");
        return root.resolve(key);
    }
    public Resource load(String key) {
        Path p = path(key);
        if (!Files.isRegularFile(p)) throw new RuleException("저장된 파일을 찾을 수 없습니다.");
        return new FileSystemResource(p);
    }
    private void delete(String key) {
        if (key == null) return;
        try { Files.deleteIfExists(path(key)); }
        catch (IOException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("파일 정리 실패: {}", key, ex); }
    }
}
