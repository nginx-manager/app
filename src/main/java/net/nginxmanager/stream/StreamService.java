package net.nginxmanager.stream;

import net.nginxmanager.apply.ApplyLock;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.common.ConcurrentEditException;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileDiscardedEvent;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.RawEditedEvent;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 스트림 프록시 초안 만들기·고치기·지우기. 디스크에는 쓰지 않고, 적용(ApplyService) 때 반영된다.
 * 사이트와 같이 적용 잠금 안에서 트랜잭션째 실행한다.
 */
@Service
public class StreamService {

    private final StreamProxyRepository streamRepository;
    private final ConfigFileRepository configFileRepository;
    private final ConfigFileStore store;
    private final StreamConfigRenderer renderer;
    private final SiteRepository siteRepository;
    private final ApplicationEventPublisher events;
    private final ApplyLock applyLock;
    private final TransactionTemplate tx;

    public StreamService(StreamProxyRepository streamRepository, ConfigFileRepository configFileRepository,
                         ConfigFileStore store, StreamConfigRenderer renderer, SiteRepository siteRepository,
                         ApplicationEventPublisher events, ApplyLock applyLock, TransactionTemplate tx) {
        this.streamRepository = streamRepository;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.renderer = renderer;
        this.siteRepository = siteRepository;
        this.events = events;
        this.applyLock = applyLock;
        this.tx = tx;
    }

    private <T> T locked(Supplier<T> work) {
        return applyLock.withLock(() -> tx.execute(status -> work.get()));
    }

    public StreamProxy create(StreamForm form, String actor) {
        return locked(() -> {
            requireFreePort(form);
            String path = StreamPaths.of(form.protocol(), form.listenPort());
            if (configFileRepository.findByPath(path).isPresent() || store.read(path) != null) {
                throw new PortTakenException(form.listenPort(), path);
            }
            StreamProxy stream = new StreamProxy(form.protocol(), form.listenPort());
            stream.applyForm(form);
            streamRepository.save(stream);
            ConfigFile file = configFileRepository.save(new ConfigFile(path, true));
            file.stageContent(renderer.render(form));
            events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "stream:" + path));
            return stream;
        });
    }

    /** 프로토콜과 포트는 파일 이름이라 바꾸지 않는다(지우고 새로 만든다). */
    public StreamProxy update(long id, StreamForm form, String actor) {
        return locked(() -> {
            StreamProxy stream = load(id);
            requireVersion(stream.getVersion(), form.version(), "stream " + stream.getConfigPath());
            if (stream.isRaw()) {
                throw new IllegalStateException("raw streams are edited in the file editor");
            }
            StreamForm fixed = new StreamForm(stream.getProtocol(), stream.getListenPort(), form.backends(),
                    form.loadBalancing(), form.connectTimeout(), form.idleTimeout(), form.note(), form.version(),
                    form.allowFrom(), form.denyFrom(), form.proxyProtocol());
            stream.applyForm(fixed);
            ConfigFile file = configFileRepository.findByPath(stream.getConfigPath())
                    .orElseGet(() -> configFileRepository.save(new ConfigFile(stream.getConfigPath(), true)));
            file.stageContent(renderer.render(fixed));
            events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "stream:" + stream.getConfigPath()));
            return stream;
        });
    }

    /** 파일을 지우는 초안을 만든다. 스트림은 적용이 끝나면 목록에서 사라진다. */
    public void delete(long id, long expectedVersion, String actor) {
        locked(() -> {
            StreamProxy stream = load(id);
            requireVersion(stream.getVersion(), expectedVersion, "stream " + stream.getConfigPath());
            ConfigFile file = configFileRepository.findByPath(stream.getConfigPath()).orElse(null);
            if (file == null || store.read(stream.getConfigPath()) == null) {
                // 아직 디스크에 쓰지 않은 새 스트림
                if (file != null) {
                    configFileRepository.delete(file);
                }
                streamRepository.delete(stream);
            } else {
                file.stageDelete();
            }
            events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE,
                    "stream:" + stream.getConfigPath() + " delete"));
            return null;
        });
    }

    /** 초안을 버리면 새 스트림은 지우고, 고친 스트림은 마지막으로 반영된 값으로 되돌린다. */
    @EventListener
    public void onDiscard(ConfigFileDiscardedEvent event) {
        if (StreamPaths.keyOf(event.path()) == null) {
            return;
        }
        streamRepository.findByConfigPath(event.path()).ifPresent(stream -> {
            if (event.removed()) {
                streamRepository.delete(stream);
            } else if (!stream.isRaw()) {
                stream.restoreApplied();
            }
        });
    }

    /** 원문 편집기에서 고친 스트림 파일은 폼이 다시 덮어쓰지 않게 원문 전용으로 바꾼다. */
    @EventListener
    public void onRawEdit(RawEditedEvent event) {
        streamRepository.findByConfigPath(event.path()).ifPresent(StreamProxy::markRaw);
    }

    private void requireFreePort(StreamForm form) {
        if (streamRepository.existsByProtocolAndListenPort(form.protocol(), form.listenPort())) {
            throw new PortTakenException(form.listenPort(),
                    StreamPaths.of(form.protocol(), form.listenPort()));
        }
        if (form.protocol() == StreamProtocol.TCP) {
            for (Site site : siteRepository.findAll()) {
                if (site.getListenHttpPort() == form.listenPort()) {
                    throw new PortTakenException(form.listenPort(), site.getPrimaryDomain());
                }
            }
        }
    }

    StreamProxy load(long id) {
        return streamRepository.findById(id).orElseThrow(() -> new NotFoundException("stream " + id));
    }

    private static void requireVersion(long actual, Long expected, String target) {
        if (expected == null || actual != expected) {
            throw new ConcurrentEditException(target);
        }
    }
}
