package com.ops.deploy.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.crypto.CredentialCryptoService;
import com.ops.deploy.deploy.DeploymentEntity;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.deploy.ServiceInstanceRepository;
import com.ops.deploy.security.ResourceAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ServerService 单测：mock ServerRepository + mock PortChecker（不触发真实网络），
 * CredentialCryptoService 用真实实例，保证加密链路真实。
 */
@ExtendWith(MockitoExtension.class)
class ServerServiceTest {

    @Mock
    private ServerRepository serverRepository;

    @Mock
    private PortChecker portChecker;

    @Mock
    private DeploymentRepository deploymentRepository;

    @Mock
    private ServiceInstanceRepository serviceInstanceRepository;

    @Mock
    private ResourceAccessService accessService;

    private CredentialCryptoService crypto;
    private ServerService service;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        crypto = new CredentialCryptoService("test-kek-for-service");
        service = new ServerService(serverRepository, crypto, portChecker,
                deploymentRepository, serviceInstanceRepository, accessService);
    }

    private ServerEntity buildEntity(Long id, String name, byte[] cipher) {
        ServerEntity e = new ServerEntity();
        e.setId(id);
        e.setName(name);
        e.setIp("10.0.0.1");
        e.setSshPort(22);
        e.setSshUser("deploy");
        e.setAuthType(AuthType.PASSWORD.name());
        e.setCredentialCipher(cipher);
        e.setEnvTag("dev");
        e.setStatus(ServerStatus.UNKNOWN.name());
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private ServerCreateRequest createRequest(String name) {
        ServerCreateRequest req = new ServerCreateRequest();
        req.setName(name);
        req.setIp("10.0.0.1");
        req.setSshUser("deploy");
        req.setPassword("P@ss123");
        return req;
    }

    @Test
    void create_shouldReturnVoAndStoreEncryptedCredential() throws Exception {
        ServerCreateRequest req = createRequest("web-01");
        when(serverRepository.existsByName("web-01")).thenReturn(false);
        when(serverRepository.save(any(ServerEntity.class))).thenAnswer(inv -> {
            ServerEntity e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });

        ServerVO vo = service.create(req, null);

        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getName()).isEqualTo("web-01");
        assertThat(vo.getSshPort()).isEqualTo(22);
        assertThat(vo.getSshUser()).isEqualTo("deploy");
        assertThat(vo.getAuthType()).isEqualTo(AuthType.PASSWORD.name());
        assertThat(vo.getEnvTag()).isEqualTo("dev");
        assertThat(vo.getStatus()).isEqualTo(ServerStatus.UNKNOWN.name());

        ArgumentCaptor<ServerEntity> captor = ArgumentCaptor.forClass(ServerEntity.class);
        verify(serverRepository).save(captor.capture());
        ServerEntity saved = captor.getValue();
        // 落库的是密文，且可被 crypto 解密回原密码
        assertThat(saved.getCredentialCipher()).isNotEmpty();
        assertThat(crypto.decrypt(saved.getCredentialCipher())).isEqualTo("P@ss123");

        // VO 序列化后绝不泄露明文密码：凭证值不出现，且结构上无 password 字段
        // （authType 的值 "PASSWORD" 是认证方式枚举，不是凭证，属正常数据）
        String json = objectMapper.writeValueAsString(vo);
        assertThat(json).doesNotContain("P@ss123");
        List<String> fields = new ArrayList<>();
        objectMapper.readTree(json).fields().forEachRemaining(e -> fields.add(e.getKey()));
        assertThat(fields).noneMatch(f -> "password".equalsIgnoreCase(f));
    }

    @Test
    void create_shouldPersistCreatedBy() {
        ServerCreateRequest req = createRequest("web-03");
        when(serverRepository.existsByName("web-03")).thenReturn(false);
        when(serverRepository.save(any(ServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(req, 42L);

        ArgumentCaptor<ServerEntity> captor = ArgumentCaptor.forClass(ServerEntity.class);
        verify(serverRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(42L);
    }

    @Test
    void create_duplicateName_shouldThrowConflict() {
        when(serverRepository.existsByName("dup")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest("dup"), null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void create_sshPortAndEnvTagNull_shouldDefault() {
        ServerCreateRequest req = createRequest("web-02");
        req.setSshPort(null);
        req.setEnvTag(null);
        when(serverRepository.existsByName("web-02")).thenReturn(false);
        when(serverRepository.save(any(ServerEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(req, null);

        ArgumentCaptor<ServerEntity> captor = ArgumentCaptor.forClass(ServerEntity.class);
        verify(serverRepository).save(captor.capture());
        ServerEntity saved = captor.getValue();
        assertThat(saved.getSshPort()).isEqualTo(22);
        assertThat(saved.getEnvTag()).isEqualTo("dev");
        assertThat(saved.getAuthType()).isEqualTo(AuthType.PASSWORD.name());
        assertThat(saved.getStatus()).isEqualTo(ServerStatus.UNKNOWN.name());
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void create_uniqueViolation_shouldFallbackToConflict() {
        when(serverRepository.existsByName("race")).thenReturn(false);
        when(serverRepository.save(any(ServerEntity.class)))
                .thenThrow(new DataIntegrityViolationException("SQLState 23505: duplicate key value"));

        assertThatThrownBy(() -> service.create(createRequest("race"), null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void get_notFound_shouldThrowNotFound() {
        when(serverRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void list_shouldReturnAllOrderedById() {
        ServerEntity e1 = buildEntity(1L, "web-01", crypto.encrypt("p1"));
        ServerEntity e2 = buildEntity(2L, "web-02", crypto.encrypt("p2"));
        when(serverRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))).thenReturn(List.of(e1, e2));

        List<ServerVO> vos = service.list();

        assertThat(vos).hasSize(2);
        assertThat(vos).extracting(ServerVO::getName).containsExactly("web-01", "web-02");
    }

    @Test
    void update_emptyPassword_shouldKeepOriginalCipher() {
        byte[] originalCipher = crypto.encrypt("orig-pass");
        ServerEntity existing = buildEntity(1L, "web-01", originalCipher);
        when(serverRepository.findById(1L)).thenReturn(Optional.of(existing));

        ServerUpdateRequest req = new ServerUpdateRequest();
        req.setPassword(null); // 不传密码 → 保留原 cipher

        ServerVO vo = service.update(1L, req);

        ArgumentCaptor<ServerEntity> captor = ArgumentCaptor.forClass(ServerEntity.class);
        verify(serverRepository).save(captor.capture());
        // cipher 对象未变（保留原凭证）
        assertThat(captor.getValue().getCredentialCipher()).isEqualTo(originalCipher);
        assertThat(crypto.decrypt(captor.getValue().getCredentialCipher())).isEqualTo("orig-pass");
        assertThat(vo.getName()).isEqualTo("web-01");
    }

    @Test
    void update_newPassword_shouldReEncrypt() {
        ServerEntity existing = buildEntity(1L, "web-01", crypto.encrypt("orig-pass"));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(existing));

        ServerUpdateRequest req = new ServerUpdateRequest();
        req.setPassword("new-pass");

        service.update(1L, req);

        ArgumentCaptor<ServerEntity> captor = ArgumentCaptor.forClass(ServerEntity.class);
        verify(serverRepository).save(captor.capture());
        assertThat(crypto.decrypt(captor.getValue().getCredentialCipher())).isEqualTo("new-pass");
    }

    @Test
    void update_newNameConflict_shouldThrowConflict() {
        ServerEntity existing = buildEntity(1L, "web-01", crypto.encrypt("orig"));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(serverRepository.existsByName("web-02")).thenReturn(true);

        ServerUpdateRequest req = new ServerUpdateRequest();
        req.setName("web-02");

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void testConnection_reachable_shouldSetOnlineAndLatencyNonNegative() {
        ServerEntity existing = buildEntity(1L, "web-01", crypto.encrypt("orig"));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(portChecker.isReachable("10.0.0.1", 22, 3000)).thenReturn(true);

        ServerTestResultVO res = service.testConnection(1L);

        assertThat(res.isReachable()).isTrue();
        assertThat(res.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(res.getStatus()).isEqualTo(ServerStatus.ONLINE.name());
        assertThat(existing.getStatus()).isEqualTo(ServerStatus.ONLINE.name());
        assertThat(existing.getLastCheckAt()).isNotNull();
        verify(portChecker, times(1)).isReachable("10.0.0.1", 22, 3000);
        verify(serverRepository, times(1)).save(existing);
    }

    @Test
    void testConnection_unreachable_shouldSetOfflineAndLatencyMinusOne() {
        ServerEntity existing = buildEntity(1L, "web-01", crypto.encrypt("orig"));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(portChecker.isReachable("10.0.0.1", 22, 3000)).thenReturn(false);

        ServerTestResultVO res = service.testConnection(1L);

        assertThat(res.isReachable()).isFalse();
        assertThat(res.getLatencyMs()).isEqualTo(-1);
        assertThat(res.getStatus()).isEqualTo(ServerStatus.OFFLINE.name());
        assertThat(existing.getStatus()).isEqualTo(ServerStatus.OFFLINE.name());
        assertThat(existing.getLastCheckAt()).isNotNull();
        verify(serverRepository, times(1)).save(existing);
    }

    @Test
    void testConnection_notFound_shouldThrowNotFound() {
        when(serverRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.testConnection(7L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void getDecryptedPassword_shouldReturnPlaintext() {
        ServerEntity existing = buildEntity(1L, "web-01", crypto.encrypt("orig-pass"));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThat(service.getDecryptedPassword(1L)).isEqualTo("orig-pass");
    }

    @Test
    void delete_notFound_shouldThrowNotFound() {
        when(serverRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void delete_referencedByDeployment_shouldThrowConflict() {
        ServerEntity existing = buildEntity(1L, "web-01", crypto.encrypt("orig"));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 存在引用该服务器的部署记录
        DeploymentEntity dep = new DeploymentEntity();
        dep.setServerId(1L);
        when(deploymentRepository.findAll()).thenReturn(List.of(dep));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.CONFLICT));
    }
}
