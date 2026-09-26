package dev.reftrace.report;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Expectation;
import dev.reftrace.crawl.Arrival;
import dev.reftrace.crawl.Landing;
import dev.reftrace.crawl.PageVisit;
import dev.reftrace.crawl.Step;
import dev.reftrace.crawl.Walker;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.Outcome;
import dev.reftrace.judge.ReferralKey;
import dev.reftrace.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReportCoverageTest {

    private static final Walker.Pages PAGES = new Walker.Pages(1330, 92, 210, 140, 3);
    private static final Report.Visits VISITS = new Report.Visits(200, 2, 1, 4, 3);
    private static final Map<String, Long> SCENARIOS = scenarios("entry", 30L, "browse", 210L);

    @Test
    void theChecksAreCountedByOutcome() {
        List<Outcome> outcomes = Stream.of(Collections.nCopies(60, Outcome.PASS),
                Collections.nCopies(2, Outcome.MISMATCH), Collections.nCopies(1, Outcome.UNTESTED),
                Collections.nCopies(4, Outcome.FAILED))
                .flatMap(List::stream).toList();

        assertThat(Report.Checks.count(outcomes)).isEqualTo(new Report.Checks(60, 2, 1, 4));
    }

    @Test
    void theVisitsAreCountedByTheWorstOfTheirChecks() {
        FoundLink link = new FoundLink(URI.create("https://trusteeplus.app.link/K2bbbbbbbbb"), "a.app", "App",
                How.HREF, true, false);
        Check pass = new Check.Pass(link);
        Check mismatch = new Check.Mismatch(link, "referral-link", ReferralKey.of("K1aaaaaaaaa"),
                List.of(new Check.Mismatch.Unmet(new Expectation.PathSegment(1), "K2bbbbbbbbb")));
        Check untested = new Check.NotTested(new Untested.Issue(UntestedReason.QR_UNREADABLE, "div.download svg",
                "blurred"));
        Check failed = new Check.Failed();
        List<PageVisit> visits = Stream.of(
                        List.of(pass, pass),
                        List.of(pass, untested, failed, mismatch),
                        List.of(untested, failed),
                        List.of(pass, untested),
                        List.<Check>of())
                .map(ReportCoverageTest::visitChecking).toList();
        Walker.Counters walk = new Walker.Counters(new Walker.Pages(1, 1, 5, 0, 1), new Walker.Links(0, 5, 0));

        assertThat(Report.Coverage.of(walk, List.of("entry"), visits).visits())
                .isEqualTo(new Report.Visits(1, 1, 1, 1, 1));
    }

    @Test
    void theVisitsAreCountedForEveryScenarioTheyWereMadeFor() {
        List<PageVisit> visits = List.of(
                visit("entry", "browse", "key-replaced"),
                visit("browse", "key-replaced"),
                visit("key-replaced"));
        Walker.Counters walk = new Walker.Counters(new Walker.Pages(1, 1, 3, 0, 2), new Walker.Links(0, 0, 0));

        Report.Coverage coverage = Report.Coverage.of(walk, List.of("entry", "browse", "key-replaced", "reload"),
                visits);

        assertThat(coverage.scenarios()).containsExactly(entry("entry", 1L), entry("browse", 2L),
                entry("key-replaced", 3L), entry("reload", 0L));
        assertThat(coverage.pages().visits()).isEqualTo(3);
    }

    @Test
    void theLinksCheckedAreTheChecksThatWereJudged() {
        Report.Coverage coverage = new Report.Coverage(PAGES, SCENARIOS, new Walker.Links(400, 62, 410),
                new Report.Checks(60, 2, 1, 4), VISITS);

        assertThat(coverage.links().checked()).isEqualTo(coverage.checks().judged());
    }

    @Test
    void aCoverageWhoseChecksDoNotAddUpIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Report.Coverage(
                PAGES, SCENARIOS, new Walker.Links(400, 63, 410), new Report.Checks(60, 2, 1, 0), VISITS));
    }

    private static PageVisit visit(String... scenarios) {
        return new PageVisit(Fixtures.profile("desktop"), List.of(scenarios),
                List.of(new Step.Enter(URI.create("https://trustee.io/"), ReferralKey.of("K1aaaaaaaaa"))),
                new Landing(URI.create("https://trustee.io/"), Arrival.ENTERED), List.of(), Map.of());
    }

    private static PageVisit visitChecking(List<Check> checks) {
        return new PageVisit(Fixtures.profile("desktop"), List.of("entry"),
                List.of(new Step.Enter(URI.create("https://trustee.io/"), ReferralKey.of("K1aaaaaaaaa"))),
                new Landing(URI.create("https://trustee.io/"), Arrival.ENTERED), checks, Map.of());
    }

    private static Map<String, Long> scenarios(String first, long firstVisits, String second, long secondVisits) {
        Map<String, Long> scenarios = new LinkedHashMap<>();
        scenarios.put(first, firstVisits);
        scenarios.put(second, secondVisits);
        return scenarios;
    }
}
