package net.nginxmanager.stream;

import net.nginxmanager.config.ConfigFileStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

/**
 * 적용·되돌리기·외부 변경 가져오기로 스트림 파일이 바뀐 뒤, 스트림 목록을 디스크 상태에 맞춘다.
 * <ul>
 *     <li>파일이 사라진 스트림은 지운다.</li>
 *     <li>스트림 없이 생긴 스트림 파일(이름 규칙에 맞는 것)은 원문 스트림으로 등록한다.</li>
 *     <li>폼으로 만든 내용과 다르면 원문 전용으로 바꾸고, 같으면 반영된 값으로 기록한다.</li>
 * </ul>
 */
@Component
public class StreamReconciler {

    private final StreamProxyRepository streamRepository;
    private final ConfigFileStore store;
    private final StreamConfigRenderer renderer;

    public StreamReconciler(StreamProxyRepository streamRepository, ConfigFileStore store,
                            StreamConfigRenderer renderer) {
        this.streamRepository = streamRepository;
        this.store = store;
        this.renderer = renderer;
    }

    /** @param changedPaths 디스크에서 내용이 바뀌거나 생기거나 사라진 파일 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(Collection<String> changedPaths) {
        for (String path : changedPaths) {
            StreamPaths.Key key = StreamPaths.keyOf(path);
            if (key == null) {
                continue;
            }
            String disk = store.read(path);
            StreamProxy stream = streamRepository.findByConfigPath(path).orElse(null);
            if (disk == null) {
                if (stream != null) {
                    streamRepository.delete(stream);
                }
                continue;
            }
            if (stream == null) {
                stream = new StreamProxy(key.protocol(), key.port());
                stream.markRaw();
                streamRepository.save(stream);
                continue;
            }
            if (stream.isRaw()) {
                continue;
            }
            if (disk.equals(renderer.render(stream.toForm()))) {
                stream.markApplied();
            } else {
                stream.markRaw();
            }
        }
    }
}
