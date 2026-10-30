package net.nginxmanager.audit;

import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 감사 로그 CSV 내려받기(030). */
class AuditExportTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;

    @BeforeEach
    void users() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    @Test
    void cellsAreQuotedAndFormulasDefused() {
        assertThat(AuditCsv.cell("site:a.example")).isEqualTo("site:a.example");
        assertThat(AuditCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(AuditCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AuditCsv.cell("line\nbreak")).isEqualTo("\"line\nbreak\"");
        assertThat(AuditCsv.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(AuditCsv.cell("+1")).isEqualTo("'+1");
        assertThat(AuditCsv.cell("-1")).isEqualTo("'-1");
        assertThat(AuditCsv.cell("@cmd")).isEqualTo("'@cmd");
        assertThat(AuditCsv.cell("")).isEmpty();
    }

    @Test
    void exportsTheFilteredRowsAsCsv() throws Exception {
        auditLogRepository.save(new AuditLog(null, "admin", AuditAction.APPLY, "version:1", AuditResult.SUCCESS, null));
        auditLogRepository.save(new AuditLog(null, "admin", AuditAction.APPLY, "version:2", AuditResult.FAILURE,
                "VALIDATION_FAILED, line 3"));
        auditLogRepository.save(new AuditLog(null, "eve", AuditAction.LOGIN, null, AuditResult.FAILURE, "=cmd|' /C calc'!A0"));

        MvcResult result = mvc.perform(get("/audit/export").param("result", "FAILURE")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.matchesPattern(
                        "attachment; filename=\"audit-\\d{8}\\.csv\"")))
                .andReturn();
        String csv = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("﻿시각,사용자,역할,동작,대상,결과,내용\r\n");
        String[] lines = csv.substring(1).split("\r\n");
        assertThat(lines).hasSize(3);
        // 최근 것이 먼저, 성공은 빠진다
        assertThat(lines[1]).contains("eve,,로그인,,실패,'=cmd|' /C calc'!A0");
        assertThat(lines[2]).contains("admin,,설정 적용,version:2,실패,\"VALIDATION_FAILED, line 3\"");
        assertThat(csv).doesNotContain("version:1");
    }

    @Test
    void membersCannotExport() throws Exception {
        mvc.perform(get("/audit/export").with(user("member").roles("MEMBER"))).andExpect(status().isForbidden());
    }
}
