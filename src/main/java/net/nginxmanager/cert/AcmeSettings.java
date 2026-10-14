package net.nginxmanager.cert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

/** Let's Encrypt 계정 설정. 한 행만 쓴다. 계정 키는 /data/acme 아래 파일로 둔다. */
@Entity
@Table(name = "acme_settings")
public class AcmeSettings extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AcmeEnvironment environment = AcmeEnvironment.PRODUCTION;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    public AcmeSettings() {
    }

    void update(AcmeEnvironment environment, String contactEmail) {
        this.environment = environment;
        this.contactEmail = contactEmail == null || contactEmail.isBlank() ? null : contactEmail.trim();
    }

    public AcmeEnvironment getEnvironment() {
        return environment;
    }

    public String getContactEmail() {
        return contactEmail;
    }
}
