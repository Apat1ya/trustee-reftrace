# syntax=docker/dockerfile:1

# Stage 1 - build the application jar with the project's own Maven wrapper.
# A plain JDK 25 image is used instead of a maven:* image so that the Maven version
# is the one pinned in .mvn/wrapper/maven-wrapper.properties and nothing else.
FROM eclipse-temurin:25-jdk AS build

# Never let Maven or Playwright pull browsers while building: the runtime stage
# installs the ones the profiles use.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    MAVEN_OPTS="-Dmaven.artifact.threads=8"

WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

# Tests need real browsers and a database, so they run in CI, not here.
# The cache mount keeps the 200 MB Playwright driver bundle out of the image layers
# and makes rebuilds fast.
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -B -ntp -DskipTests clean package \
    && cp target/*.jar /build/app.jar

# The jar split into its libraries and the application, so that a new build changes
# only a small layer and the libraries' layer stays on the host from the last deploy.
# Then the Playwright driver of the jar's own Playwright version, laid out as a directory:
# its Node.js package from the driver jar and the Linux Node.js from the driver bundle.
# The runtime stage installs the browsers with it, so they always match the library.
# The driver bundle itself, Node.js for every platform, is left out of the libraries:
# with PLAYWRIGHT_DRIVER_DIR set Playwright never reads it.
RUN java -Djarmode=tools -jar /build/app.jar extract --destination /build/app \
    && mkdir /build/unpacked /build/driver \
    && cd /build/unpacked \
    && jar xf /build/app/lib/driver-[0-9]*.jar driver/package \
    && jar xf /build/app/lib/driver-bundle-*.jar driver/linux/node \
    && mv driver/package driver/linux/node /build/driver/ \
    && chmod +x /build/driver/node \
    && rm /build/app/lib/driver-bundle-*.jar

# Stage 2 - runtime on a plain JRE with only the browsers the device profiles use:
# Chromium, which runs headless as the headless shell, and WebKit. Firefox has no
# mobile emulation, so no profile can use it.
FROM eclipse-temurin:25-jre-noble

COPY --from=build /build/driver /ms-playwright-driver

# The browsers, the system libraries and fonts they need, curl for the health check,
# and a user that is not root to run them (uid 1001 like the Playwright images, which
# own the existing runs volume).
RUN PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
        /ms-playwright-driver/node /ms-playwright-driver/package/cli.js \
        install --with-deps --only-shell chromium webkit \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 1001 --create-home pwuser

# PLAYWRIGHT_DRIVER_DIR points Playwright at the driver above. Playwright-Java would
# otherwise extract its node driver out of driver-bundle.jar into a temp directory on
# the first Playwright.create(), and that jar is not in the image. With the driver
# preinstalled nothing is unpacked and start-up is a second faster.
#
# PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD makes a missing browser fail loudly instead of
# quietly downloading 400 MB into a running container.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_DRIVER_DIR=/ms-playwright-driver \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    SERVER_PORT=8080 \
    MANAGEMENT_SERVER_PORT=8081

WORKDIR /app

# runs/   - one directory per run: report.json, screenshots/, traces/
RUN mkdir -p /app/runs && chown -R pwuser:pwuser /app

COPY --from=build --chown=pwuser:pwuser /build/app/lib /app/lib
COPY --from=build --chown=pwuser:pwuser /build/app/app.jar /app/app.jar

USER pwuser

# 8080 - REST trigger and reports, 8081 - actuator (health, Prometheus scrape)
EXPOSE 8080 8081

# Liveness only: a run that found issues on the website is still a healthy monitor,
# and readiness would flap while a six-hour run holds the worker pool.
# start-period is generous because the JVM shares the host with several browsers.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8081/actuator/health/liveness || exit 1

# The heap is deliberately small: the browsers, not the JVM, need the container's
# memory (budget roughly 1 GB per browser worker plus 1 GB).
# Extra flags can be added from outside through JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", \
            "-XX:MaxRAMPercentage=25", \
            "-XX:+ExitOnOutOfMemoryError", \
            "-jar", "/app/app.jar"]
