package dev.reftrace.crawl;

import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.judge.Check;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class Screenshots {

    public static final String DIRECTORY = "screenshots";

    private static final Logger log = LoggerFactory.getLogger(Screenshots.class);
    private static final int MAX_SLUG_LENGTH = 40;

    private final Path directory;
    private final Map<String, Integer> taken = new HashMap<>();

    Screenshots(Path runDirectory) {
        this.directory = runDirectory.resolve(DIRECTORY);
    }

    @Nullable Path save(DeviceProfile profile, URI page, Check check, byte[] png) {
        String name = slug(profile.name()) + "--" + pageSlug(page) + "--" + what(check);
        Path file = directory.resolve(name + "-" + taken.merge(name, 1, Integer::sum) + ".png");
        try {
            Files.createDirectories(directory);
            return Files.write(file, png);
        } catch (IOException e) {
            log.warn("the screenshot {} could not be written: {}", file, e.toString());
            return null;
        }
    }

    static String pageSlug(URI page) {
        String slug = slug(Objects.requireNonNullElse(page.getPath(), ""));
        return slug.isEmpty() ? "home" : slug;
    }

    private static String what(Check check) {
        String what = switch (check) {
            case Check.Mismatch mismatch -> {
                String text = slug(mismatch.link().text());
                yield text.isEmpty() ? slug(Objects.requireNonNullElse(mismatch.link().url().getHost(), "")) : text;
            }
            case Check.NotTested notTested -> slug(notTested.untested().reason().name());
            case Check.Pass _, Check.Failed _ -> "";
        };
        return what.isEmpty() ? "link" : what;
    }

    static String slug(String text) {
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.length() > MAX_SLUG_LENGTH ? slug.substring(0, MAX_SLUG_LENGTH).replaceAll("-$", "") : slug;
    }
}
