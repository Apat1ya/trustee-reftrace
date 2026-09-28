<a id="readme-top"></a>

<div align="center">

<h1>Trustee-reftrace</h1>

<p>
  Daily monitor that proves a referral key handed to a site as <code>?r=&lt;key&gt;</code> reaches the
  App Store and Google Play install links on every page, on every device.
</p>

[![Java][java-badge]][java-url]
[![Spring Boot][spring-badge]][spring-url]
[![Playwright][playwright-badge]][playwright-url]
[![Prometheus][prometheus-badge]][prometheus-url]
[![Grafana][grafana-badge]][grafana-url]
[![Docker][docker-badge]][docker-url]

</div>

Reftrace navigates a website in real browsers just as users do on various devices and verifies that each 
link leads where it should. The website, links, and rules are defined in the configuration, so the same 
crawl can track UTM tags after a release or detect a cached page with an outdated key in its links. For 
each broken link, the report lists the steps that led to it: a developer can follow the same path and see 
the problem firsthand.

## Setup and operation

### Getting started

**Prerequisites:** JDK 25. Maven comes with the wrapper. Docker is needed only for the image.

```bash
git clone git@github.com:Apat1ya/trustee-reftrace.git && cd trustee-reftrace

# Playwright browsers, once
./mvnw -DskipTests compile exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium webkit"

./mvnw -DskipTests package
```

### Usage

```bash
# full run against the configured site (hours)
java -jar target/trustee-reftrace-0.1.0-SNAPSHOT.jar --run-once

# short run
java -jar target/trustee-reftrace-0.1.0-SNAPSHOT.jar --run-once --reftrace.limits.run.time=10m --reftrace.limits.run.depth=2

# the same in Docker
docker build -t trustee-reftrace .
docker run --rm --init --ipc=host -v "$PWD/runs:/app/runs" trustee-reftrace --run-once
```

Exit codes: `0` all passed · `1` at least one mismatch · `2` the run cannot be trusted (a failed
check, pages left unvisited by the time limit, nothing checked, or no report written).

### Configuration

[application.yml](src/main/resources/application.yml) documents every key. The configuration is
validated at startup; override a key as `--reftrace.a.b=…` or `REFTRACE_A_B`.

| Key (`reftrace.`) | Default | Purpose |
|---|---|---|
| `start` | site root + sitemap | start pages and sitemaps |
| `key-param` | `r` | query parameter that carries the key into the site |
| `routes` | see file | which links are followed, checked or forbidden, and what they must carry |
| `scenarios`, `coverage` | see file | the visitor's steps; how often a page is revisited |
| `profiles` | 3 devices | engine, user agent, viewport, touch |
| `browser.unwalked-blocks`, `reveal` | see file | site-specific selectors: blocks not clicked through, menus and dialogs to open or close |
| `parallel-browsers` | 6 | concurrent browser contexts, ≈ 1 GB RAM each |
| `limits.run.time` | 6h | run time limit |
| `limits.run.depth` / `pages` / `visits` | 0 = unlimited | walk bounds |
| `qr-check` | true | decode QR codes as links |
| `report.screenshots` / `traces` | true | evidence files |
| `report.retention.run` / `screenshots` / `traces` | 7d | deleted when the next run starts; `0` keeps forever |
| `schedule.enabled` / `cron` / `zone` | false / `0 0 3 * * *` / UTC | daily run of a long-lived process |

### Deployment

[`.github/deploy/compose.yml`](.github/deploy/compose.yml) defines the stand: the monitor on a daily
schedule, Tempo, Prometheus, Alertmanager, Grafana with provisioned dashboards, and Caddy.
Caddy exposes the only port, 443, with one basic-auth password in front of everything.

A push to `master` runs [deploy.yml](.github/workflows/deploy.yml): CI → image to GHCR →
[deploy.sh](.github/scripts/deploy.sh) over SSH, which copies the stand, writes `.env` and the secret
files, and restarts the containers.

| Setting | Kind | Purpose |
|---|---|---|
| `DEPLOY_HOST`, `DEPLOY_SSH_KEY` | secret | target host; key of a user in the `docker` group |
| `REPORTS_PASSWORD` | secret | stand password; only its bcrypt hash reaches the host |
| `TELEGRAM_BOT_TOKEN` | secret | alert bot |
| `SITE_ADDRESS` | variable | host name or IP; a host name gets a Let's Encrypt certificate |
| `TELEGRAM_CHAT_ID` | variable | alert chat |
| `DEPLOY_USER`, `PARALLEL_BROWSERS` | variable | optional; default `ubuntu`, `2` |

### Operations

| Entry point | Shows |
|---|---|
| Grafana **Reftrace — run** | a selected run's findings by page, untested items by reason, outcome of each run over two weeks |
| Grafana **Reftrace monitor** | running or not, run in progress, last run end, time since the last report, keys lost, pages visited |
| Grafana **Where the run's time goes** | per-visit traces: slow stages, timeouts, retries |
| `/alertmanager/` | active alerts, also sent to Telegram |
| `/reports/` | raw `report.json` files and traces |
| `/prometheus/` | `reftrace_run_last_*`, `reftrace_visits_total`, alert rules |

Mismatches found on the site are not alerted: a known permanent site bug would fire every day. They
belong to the report.

On the host, in `~/reftrace`:

```bash
docker compose ps
docker compose logs --tail=200 reftrace
docker compose run --rm -d --name reftrace-run-once reftrace --run-once   # extra run now
docker compose restart reftrace                                           # stuck run
```

The schedule is `REFTRACE_SCHEDULE_CRON` in `compose.yml` (UTC). Change it there and push: the deploy
rewrites `.env`. Traces of failed visits (`/reports/<run>/traces/*.zip`) open at
<https://trace.playwright.dev>.

## How it works

1. **Start pages:** the configured pages plus every URL in the configured sitemaps.
2. **Walk:** each start page is entered with a fresh random key. Links of the site's own hosts are
   clicked recursively along the configured scenarios.
3. **Scan:** once a page's links stop changing, every `<a>` (hidden responsive duplicates included) and
   every QR code is read. A link is judged by its `href` and by where a real click on it goes.
4. **Judge:** the last matching route defines what the link must carry.
5. **Output:** `runs/<run-id>/report.json` with screenshots and Playwright traces, metrics to
   Prometheus, and one trace per page visit to Tempo.

The monitor never modifies a link and causes no side effects. A route with `follow: false` is never
left: the browser answers the request itself with `204`. For example, the default configuration has

```yaml
routes:
  - name: branch-links
    match: ["*.app.link", app.link, "*.branch.io", t.ki]
    follow: false
```

so a click on an install link is observed together with the URL it goes to, but the request never
reaches Branch and no attribution click is counted for the app. 
Analytics trackers listed in `blocked-analytics-hosts` are blocked, so the walk does not show up in the site's statistics.

| Outcome | Meaning |
|---|---|
| `pass` | the requirement holds; the only success |
| `mismatch` | key missing, foreign (`null`, the site's default key, a hard-coded slug), altered or stale, or the link goes where no link may go |
| `untested` | the site prevented the check: HTTP 4xx/5xx, a QR code that is unreadable or not shown |
| `failed` | the monitor could not check: the page did not load in time or failed to navigate, a click went nowhere or could not be made, the link disappeared or the page changed before the click, the browser crashed, or an internal error |

Technical failures are retried before they count as `failed`; the number of attempts and the pause
between them are set in `limits.visit`.

### Coverage

What is walked is set in the configuration, not in the code:

- **Devices** (`profiles`): any number of browser profiles, Chromium or WebKit, each with its own
  viewport, user agent and touch support.
- **Scenarios** (`scenarios`): any sequence of the steps `enter`, `click`, `click*` (any number of
  clicks), `reload` and `enter-new-key`. Every start page is walked through every scenario.
- **Bounds** (`limits.run`): depth, number of pages, number of visits and time; each can be unlimited.
- **Deduplication** (`coverage`): a page is visited once, once per way of arriving at it, or once per
  link leading to it.

The scenarios of a profile are merged into one prefix tree, so a path shared by several scenarios is
visited once and counted for each of them. Links inside `browser.unwalked-blocks` are judged but not
clicked as walk steps.

### Built with

Java 25 · Spring Boot 4.1 · Playwright for Java 1.63 (Chromium, WebKit) · Micrometer + OpenTelemetry ·
Prometheus · Alertmanager · Grafana (Infinity plugin) · Tempo · Caddy · Docker Compose · GitHub Actions

## Report format

```
runs/20260927T133543Z-d0e4/
  report.json
  screenshots/   outlined problem elements
  traces/        Playwright traces of failed visits
```

```jsonc
{
  "run": { "id": "20260927T133543Z-d0e4", "site": "https://trustee.io",
           "startedAt": "2026-09-27T13:35:43Z", "finishedAt": "2026-09-27T13:40:44Z",
           "profiles": ["desktop-chrome", "android-pixel", "iphone-safari"] },
  "coverage": {
    "pages":     { "start": 1283, "unique": 132, "visits": 398, "repeats": 26833, "maxDepth": 1 },
    "scenarios": { "entry": 398, "browse": 398, "key-replaced": 398, "reload": 398 },
    "links":     { "followed": 93207, "checked": 96498, "ignored": 0 },
    "checks":    { "pass": 95449, "mismatch": 1049, "untested": 404, "failed": 4 },
    "visits":    { "pass": 0, "mismatch": 181, "untested": 217, "failed": 0, "nothingToCheck": 0 }
  },
  "pages": [{
    "page": "https://trustee.io/buy/",
    "profile": "android-pixel",
    "scenarios": ["entry", "browse", "key-replaced", "reload"],
    "path": ["enter https://trustee.io/buy/?r=XlRpB2h5UZC"],
    "problems": [{
      "route": "referral-link",
      "href": "https://trusteeplus.app.link/null?r=XlRpB2h5UZC",
      "selector": "a.style_button__6Li0Y.style_calculation__button__e_DjJ",
      "screenshot": "screenshots/android-pixel--buy--buy-btc-1.png",
      "checked": { "path-segment": 1 },
      "expected": "XlRpB2h5UZC",
      "actual": "null"
    }],
    "untested": [{
      "reason": "qrHidden", "selector": "svg",
      "detail": "the QR code svg 169px is not shown on this device",
      "screenshot": "screenshots/android-pixel--buy--qr-hidden-1.png"
    }]
  }]
}
```

- `pages` lists only visits with a problem or an untested item; passing visits are counted in
  `coverage`.
- A problem is reproduced from `profile` and `path` (the steps from the entry URL with its key).
  `checked` names the broken requirement; `expected` / `actual` are the keys.
- Untested reasons `httpStatus`, `qrUnreadable` and `qrHidden` count as `untested`. The others count
  as `failed`: `pageLoadTimeout`, `navigationError`, `clickNoNavigation`, `clickFailed`,
  `linkNotFound`, `domChanged`, `browserCrash`, `internal`.

## Verification

### Does it detect a lost key

A `pass` on the live site proves nothing about detection. `ReportTellsKeyLossFromInabilityToCheckTest`
runs the production pipeline (walker, judge, `report.json` on disk) against a scripted site, one case
per row.

```bash
./mvnw verify                                                       # all tests, as in CI
./mvnw test -Dtest=ReportTellsKeyLossFromInabilityToCheckTest
```

| Case | Outcome |
|---|---|
| the link carries the key | `pass` |
| one link lost the key and one carries another | `mismatch` |
| the site answers HTTP 500 on every attempt | `untested` |
| the page never loads on any attempt | `failed`, never `mismatch` |

CI ([ci.yml](.github/workflows/ci.yml)) runs it on every push.

### Is the deployed monitor healthy

A short run against the live site checks the whole chain end to end: exit code `0` or `1` and a
`report.json` with non-zero `coverage.checks` mean the monitor works; `2` means it could not be trusted.
On the stand the **Reftrace monitor** dashboard shows the same, and alerts fire when it stops working
([rules](.github/deploy/prometheus/rules/reftrace-alerts.yml),
[rule tests](.github/deploy/prometheus/rules/reftrace-alerts.test.yml) for `promtool test rules`):

| Alert | Condition |
|---|---|
| `ReftraceDown` | no metrics from the monitor for 10 min |
| `ReftraceNoSuccessfulRun26h` | the last run with a report ended more than 26 h ago |
| `ReftraceNoSuccessfulRunEver` | up for 26 h without a single run that wrote a report |
| `ReftraceRunNotTrustworthy` | the last run wrote no report, or more than 10 % of its checks `failed` |
| `ReftraceRunStuck` | a run started more than 7 h ago and has not ended |
| `ReftraceCoverageDropped` (warning) | pages visited < 70 % of the week's maximum |

## Project structure

Packages under `dev.reftrace` follow the data flow:

| Package | Responsibility |
|---|---|
| `config` | validated `reftrace.*` configuration: routes, scenarios, limits, profiles |
| `sitemap` | start pages from configuration and sitemaps |
| `crawl` | scenario tree, walk, retries, parallel browser lanes |
| `browse` | Playwright: profiles, network guard, settle wait, link and QR discovery, clicks, screenshots |
| `judge` | does a link meet its route's requirement |
| `report` | `report.json`, retention |
| `run` | one run end to end, schedule, `--run-once`, metrics |

| Path | Content |
|---|---|
| `.github/deploy/` | the stand: compose, Caddy, Prometheus rules, Alertmanager, Grafana provisioning |
| `.github/workflows/` | CI and deployment |

[java-badge]: https://img.shields.io/badge/Java-25-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white
[java-url]: https://openjdk.org/projects/jdk/25/
[spring-badge]: https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white
[spring-url]: https://spring.io/projects/spring-boot
[playwright-badge]: https://img.shields.io/badge/Playwright-1.63-2EAD33?style=for-the-badge&logo=playwright&logoColor=white
[playwright-url]: https://playwright.dev/java/
[prometheus-badge]: https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white
[prometheus-url]: https://prometheus.io/
[grafana-badge]: https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=grafana&logoColor=white
[grafana-url]: https://grafana.com/
[docker-badge]: https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white
[docker-url]: https://www.docker.com/
