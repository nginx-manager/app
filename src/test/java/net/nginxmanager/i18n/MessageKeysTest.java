package net.nginxmanager.i18n;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 화면 문구는 4개 언어(ko 기본, en, ja, zh-CN)가 같은 키를 모두 갖는다. */
class MessageKeysTest {

    static Set<String> keys(String file) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = MessageKeysTest.class.getResourceAsStream("/" + file)) {
            assertThat(in).as(file).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties.stringPropertyNames();
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages_en.properties", "messages_ja.properties", "messages_zh_CN.properties"})
    void everyLanguageHasTheSameKeys(String file) throws IOException {
        Set<String> base = keys("messages.properties");
        Set<String> other = keys(file);

        assertThat(other).as("%s에 없는 키", file).containsAll(base);
        assertThat(base).as("messages.properties에 없는 키(%s에만 있음)", file).containsAll(other);
    }
}
