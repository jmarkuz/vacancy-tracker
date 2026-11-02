package com.vacancytracker.common.logging;

import com.vacancytracker.common.LogMasking;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Finds the secret values in the configuration while the application starts and hands them to LogMasking, so
 * they are hidden wherever they appear in a log line. A property is a secret when its name ends in password,
 * secret, token, key or api-key (in any spelling), which covers APP_ACCESS_TOKEN, the IMAP password, the Telegram
 * bot token, the Adzuna keys and the datasource password, and any later setting named in the same way.
 */
public class SecretRegistration implements EnvironmentPostProcessor {

    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(password|secret|token|[._-]key|api[._-]?key|app[._-]?key)$");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> values = new ArrayList<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (SECRET_NAME.matcher(name).matches()) {
                        Object value = enumerable.getProperty(name);
                        if (value instanceof String s) {
                            values.add(s);
                        }
                    }
                }
            }
        }
        LogMasking.register(values);
    }
}
