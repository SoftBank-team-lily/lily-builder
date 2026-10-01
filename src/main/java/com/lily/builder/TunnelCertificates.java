package com.lily.builder;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 에이전트의 DB 터널 공개키에 SSH CA 로 인증서를 서명한다. 사용자 PC 에 개인키를 나눠 주지 않기 위해서다.
 *
 * <p>배스천의 lily-tunnel 은 이 CA 를 {@code cert-authority,restrict,port-forwarding,permitopen="RDS:5432"} 로 믿는다.
 * 그래서 인증서로 들어와도 RDS 포트 포워딩만 된다. 인증서에는 포워딩 외의 권한을 넣지 않는다.
 */
@Component
public class TunnelCertificates {

    private static final Pattern PUBLIC_KEY = Pattern.compile("ssh-ed25519 [A-Za-z0-9+/]{60,80}={0,2}");

    private final PlatformProperties.Tunnel settings;
    private volatile Path caFile;

    public TunnelCertificates(PlatformProperties props) {
        this.settings = props.tunnel();
    }

    public boolean enabled() {
        return settings.configured();
    }

    public PlatformProperties.Tunnel settings() {
        return settings;
    }

    /**
     * @param publicKey 에이전트가 hello 로 보낸 {@code ssh-ed25519 AAAA... [comment]}
     * @return {@code ssh-ed25519-cert-v01@openssh.com ...} 한 줄
     */
    public String sign(String agentKey, String publicKey) {
        if (!enabled()) {
            throw new IllegalStateException("DB 터널 CA 가 설정되지 않았다");
        }
        String[] parts = publicKey == null ? new String[0] : publicKey.trim().split("\\s+");
        if (parts.length < 2 || !PUBLIC_KEY.matcher(parts[0] + " " + parts[1]).matches()) {
            throw new IllegalArgumentException("ssh-ed25519 공개키가 아니다");
        }
        if (!AgentTokens.KEY.matcher(agentKey).matches()) {
            throw new IllegalArgumentException("에이전트 key 가 아니다");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lily-cert");
            Path pub = dir.resolve("agent.pub");
            Files.writeString(pub, parts[0] + " " + parts[1] + " agent-" + agentKey + "\n");
            run(List.of("ssh-keygen", "-q", "-s", ca().toString(),
                    "-I", "agent-" + agentKey,
                    "-n", settings.sshUser(),
                    "-V", "-5m:+" + Math.max(1, settings.validityHours()) + "h",
                    "-O", "clear", "-O", "permit-port-forwarding",
                    pub.toString()));
            return Files.readString(dir.resolve("agent-cert.pub")).trim();
        } catch (IOException e) {
            throw new IllegalStateException("인증서를 만들지 못했다: " + e.getMessage(), e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /** ssh-keygen 은 다른 사용자가 읽을 수 있는 개인키를 거절한다. 환경변수의 키를 소유자 전용 파일로 한 번 옮긴다 */
    private synchronized Path ca() throws IOException {
        if (caFile == null) {
            Path file = Files.createTempFile("lily-ca", ".key",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            String pem = settings.caKey().replace("\\n", "\n").trim() + "\n";
            Files.writeString(file, pem, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            caFile = file;
        }
        return caFile;
    }

    private static void run(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ssh-keygen 이 끝나지 않았다");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ssh-keygen 실패: " + output.trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("ssh-keygen 을 실행하지 못했다: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // 임시 디렉터리
        }
    }
}
