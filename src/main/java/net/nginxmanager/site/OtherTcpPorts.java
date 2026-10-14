package net.nginxmanager.site;

import java.util.Optional;

/**
 * 사이트가 아닌 기능(스트림 프록시 등)이 nginx에서 쓰는 TCP 포트. 사이트 포트와 겹치면 nginx -t는 통과하지만
 * reload에서 포트를 열지 못해 되돌려지므로, 저장할 때 미리 막는다.
 */
public interface OtherTcpPorts {

    /** @return 그 포트를 쓰는 대상의 이름. 쓰지 않으면 비어 있다 */
    Optional<String> usedBy(int port);
}
