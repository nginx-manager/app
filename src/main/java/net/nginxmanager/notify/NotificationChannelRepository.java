package net.nginxmanager.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {

    List<NotificationChannel> findAllByOrderByIdAsc();

    List<NotificationChannel> findByEnabledTrue();
}
