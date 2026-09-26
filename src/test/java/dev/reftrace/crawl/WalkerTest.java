package dev.reftrace.crawl;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.PageScan;
import dev.reftrace.browse.StoredValue;
import dev.reftrace.config.CoverageMode;
import dev.reftrace.config.ExpectEntry;
import dev.reftrace.config.Expectation;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.config.Scenario;
import dev.reftrace.config.StepWord;
import dev.reftrace.judge.ReferralKey;
import dev.reftrace.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.reftrace.config.StepWord.CLICK;
import static dev.reftrace.config.StepWord.CLICK_ANY;
import static dev.reftrace.config.StepWord.ENTER;
import static dev.reftrace.config.StepWord.ENTER_NEW_KEY;
import static dev.reftrace.config.StepWord.RELOAD;
import static dev.reftrace.testsupport.Fixtures.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class WalkerTest {

    private static final ReferralKey K1 = ReferralKey.of("K1aaaaaaaaa");
    private static final ReferralKey K2 = ReferralKey.of("K2bbbbbbbbb");
    private static final RunKeys KEYS = new RunKeys(K1, K2);
    private static final URI HOME = site("/");
    private static final URI CARDS = site("/cards/");
    private static final URI FEES = site("/fees/");
    private static final Landing ENTERED_HOME = new Landing(HOME, Arrival.ENTERED);
    private static final Routes SITE_ONLY = new Routes(List.of(route(true, List.of(), "trustee.io")));

    private static final Scenario ENTRY = scenario("entry", ENTER);
    private static final Scenario REFRESH = scenario("refresh", ENTER, RELOAD);
    private static final Scenario BROWSE = scenario("browse", ENTER, CLICK_ANY);
    private static final Scenario KEY_REPLACED = scenario("key-replaced", ENTER, CLICK_ANY, ENTER_NEW_KEY, CLICK_ANY);
    private static final List<Scenario> SHIPPED = List.of(scenario("entry", ENTER), BROWSE, KEY_REPLACED,
            scenario("reload", ENTER, CLICK_ANY, RELOAD));

    private final Site owners = new Site()
            .links(HOME, CARDS)
            .links(FEES, CARDS);

    @ParameterizedTest
    @CsvSource({"ONCE_PER_PAGE, 3, 2, 1", "ONCE_PER_ARRIVAL, 4, 1, 2", "ONCE_PER_LINK, 5, 0, 2"})
    void eachCoverageModeVisitsTheOwnersExampleAsOftenAsItsKeyTellsApart(CoverageMode mode, int visits,
                                                                         int repeats, int maxDepth) {
        Walker.Walk walk = walker(mode, SITE_ONLY).walk(profile("desktop"), tree(BROWSE), List.of(HOME, CARDS, FEES));

        List<Visit> done = run(walk, owners);

        assertThat(done).hasSize(visits);
        assertThat(owners.loads).hasSize(visits);
        assertThat(walk.counters().pages()).isEqualTo(new Walker.Pages(3, 3, visits, repeats, maxDepth));
        assertThat(done).extracting(visit -> visit.landing().address()).startsWith(HOME, CARDS, FEES);
    }

    @Test
    void oncePerLinkClicksEveryLinkToTheSamePageFromItsOwnPage() {
        List<Visit> done = run(walker(CoverageMode.ONCE_PER_LINK, SITE_ONLY)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME, CARDS, FEES)), owners);

        assertThat(done).extracting(visit -> visit.task().from(), visit -> visit.landing())
                .containsExactly(
                        tuple(null, new Landing(HOME, Arrival.ENTERED)),
                        tuple(null, new Landing(CARDS, Arrival.ENTERED)),
                        tuple(null, new Landing(FEES, Arrival.ENTERED)),
                        tuple(new Landing(HOME, Arrival.ENTERED), new Landing(CARDS, Arrival.CLICKED_WITHOUT_KEY)),
                        tuple(new Landing(FEES, Arrival.ENTERED), new Landing(CARDS, Arrival.CLICKED_WITHOUT_KEY)));
    }

    @Test
    void entryVisitsTheStartPagesOnlyAndRefreshReloadsEach() {
        Walker walker = walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY);

        assertThat(run(walker.walk(profile("desktop"), tree(ENTRY), List.of(HOME, CARDS, FEES)), owners))
                .extracting(visit -> visit.landing().arrival()).containsOnly(Arrival.ENTERED).hasSize(3);
        assertThat(run(walker.walk(profile("desktop"), tree(REFRESH), List.of(HOME, FEES)),
                new Site().links(HOME, CARDS)))
                .extracting(Visit::landing)
                .containsExactly(new Landing(HOME, Arrival.ENTERED), new Landing(FEES, Arrival.ENTERED),
                        new Landing(HOME, Arrival.RELOADED), new Landing(FEES, Arrival.RELOADED));
    }

    @Test
    void aPageIsReloadedOnceForEachWayItWasReached() {
        Site site = new Site().links(HOME, CARDS).links(CARDS, HOME);

        List<Visit> done = run(walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY)
                .walk(profile("desktop"), tree(scenario("reload", ENTER, CLICK_ANY, RELOAD)), List.of(HOME)), site);

        assertThat(done).filteredOn(visit -> visit.landing().arrival() == Arrival.RELOADED)
                .extracting(visit -> visit.landing().address(), visit -> Objects.requireNonNull(visit.task().from()))
                .containsExactlyInAnyOrder(
                        tuple(HOME, new Landing(HOME, Arrival.ENTERED)),
                        tuple(CARDS, new Landing(CARDS, Arrival.CLICKED_WITHOUT_KEY)),
                        tuple(HOME, new Landing(HOME, Arrival.CLICKED_WITHOUT_KEY)));
        assertThat(done).extracting(visit -> visit.task().path())
                .allSatisfy(path -> assertThat(path).filteredOn(Step.Reload.class::isInstance).hasSizeLessThan(2));
    }

    @Test
    void theNewKeyMakesEveryPageFreshAgainAndIsExpectedFromItsEnterOn() {
        List<Visit> done = run(walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY)
                .walk(profile("desktop"), tree(KEY_REPLACED), List.of(HOME, CARDS, FEES)), owners);

        List<Visit> secondKey = done.stream().filter(visit -> visit.expectedKey().equals(K2)).toList();
        assertThat(secondKey).extracting(Visit::landing).containsExactlyInAnyOrder(
                new Landing(HOME, Arrival.ENTERED_NEW_KEY),
                new Landing(CARDS, Arrival.ENTERED_NEW_KEY),
                new Landing(FEES, Arrival.ENTERED_NEW_KEY),
                new Landing(CARDS, Arrival.CLICKED_WITHOUT_KEY));
        assertThat(done).hasSize(8).filteredOn(visit -> visit.expectedKey().equals(K1)).hasSize(4)
                .allSatisfy(visit -> assertThat(visit.task().path()).hasSize(visit.landing().arrival()
                        == Arrival.ENTERED ? 1 : 2));
        assertThat(secondKey).filteredOn(visit -> visit.landing().arrival() == Arrival.CLICKED_WITHOUT_KEY)
                .singleElement()
                .satisfies(visit -> assertThat(visit.task().path()).containsExactly(
                        new Step.Enter(HOME, K1), new Step.Enter(HOME, K2), click(CARDS)));
    }

    @Test
    void theEnterWithTheNewKeyOpensTheLandedAddressWithoutTheOldKey() {
        Site keeping = new Site().links(HOME, URI.create(CARDS + "?r=" + K1));

        List<Visit> done = run(walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY)
                .walk(profile("desktop"), tree(KEY_REPLACED), List.of(HOME)), keeping);

        assertThat(done).extracting(Visit::landing).contains(new Landing(CARDS, Arrival.CLICKED_WITH_KEY));
        assertThat(done).extracting(visit -> visit.task().path().getLast())
                .contains(new Step.Enter(CARDS, K2));
    }

    @Test
    void linksAFollowFalseRouteCoversAreNeitherEnteredNorClicked() {
        URI support = site("/support/");
        URI store = URI.create("https://trusteeplus.app.link/" + K1);
        URI facebook = URI.create("https://facebook.com/trustee");
        Routes routes = new Routes(List.of(
                route(true, List.of(), "trustee.io"),
                route(false, List.of(), "trustee.io/support/**"),
                route(false, List.of(new ExpectEntry(1, null, null, null, null, false)), "trusteeplus.app.link")));
        Site site = new Site().links(HOME, CARDS, support, store, facebook).links(support, FEES);
        Walker.Walk walk = walker(CoverageMode.ONCE_PER_ARRIVAL, routes)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME, support));

        List<Visit> done = run(walk, site);

        assertThat(done).extracting(visit -> visit.landing().address()).containsExactly(HOME, CARDS);
        assertThat(walk.counters()).isEqualTo(new Walker.Counters(
                new Walker.Pages(1, 2, 2, 0, 2), new Walker.Links(1, 1, 2)));
    }

    @Test
    void aLinkWithoutTheParameterAQueryIfPresentLooksForIsNotCounted() {
        Routes routes = new Routes(List.of(
                route(true, List.of(new ExpectEntry(null, null, "r", null, null, false)), "trustee.io")));
        URI withKey = URI.create(CARDS + "?r=" + K1);
        Site site = new Site().links(HOME, withKey, FEES);
        Walker.Walk walk = walker(CoverageMode.ONCE_PER_ARRIVAL, routes)
                .walk(profile("desktop"), tree(ENTRY), List.of(HOME));

        run(walk, site);

        assertThat(walk.counters().links()).isEqualTo(new Walker.Links(2, 1, 0));
    }

    @Test
    void aValueReadOnArrivalCountsAsACheckAndTheLinkItselfDoesNot() {
        Routes routes = new Routes(List.of(
                route(true, List.of(new ExpectEntry(null, null, null, "ref", null, false)), "trustee.io")));
        Site site = new Site().links(HOME, CARDS);
        Walker.Walk walk = walker(CoverageMode.ONCE_PER_ARRIVAL, routes)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME));

        for (Optional<Walker.Queued> next = walk.take(); next.isPresent(); next = walk.take()) {
            WalkTask task = next.get().task();
            PageScan scan = site.scan(task);
            walk.visited(task, task.path().getLast() instanceof Step.Click(FoundLink followed)
                    ? new PageScan(scan.landedUrl(), scan.links(),
                    List.of(new StoredValue(followed, "trustee.io", new Expectation.Cookie("ref"), null)),
                    scan.untested())
                    : scan);
        }

        assertThat(walk.counters().links()).isEqualTo(new Walker.Links(1, 1, 0));
    }

    @Test
    void onlyVisibleHrefLinksAreClicked() {
        Site site = new Site().links(HOME, CARDS);
        site.extra.put(HOME, List.of(
                new FoundLink(FEES, "a.hidden", "Fees", How.HREF, false, false),
                new FoundLink(site("/qr/"), "svg", "QR", How.QR, true, false)));

        List<Visit> done = run(walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME)), site);

        assertThat(done).extracting(visit -> visit.landing().address()).containsExactly(HOME, CARDS);
    }

    @Test
    void aLinkInsideAnUnwalkedBlockIsNeverAStep() {
        URI ukrainian = site("/ua/");
        Site site = new Site().links(HOME, CARDS);
        site.extra.put(HOME, List.of(
                new FoundLink(ukrainian, "aside .lang-modal a.ua", "UK", How.HREF, true, true),
                new FoundLink(FEES, "aside .lang-modal a.fees", "Fees", How.HREF, true, true),
                link(FEES)));

        List<Visit> done = run(walker(CoverageMode.ONCE_PER_LINK, SITE_ONLY)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME)), site);

        assertThat(done).extracting(visit -> visit.landing().address()).containsExactly(HOME, CARDS, FEES);
        assertThat(paths(done)).contains(List.of(new Step.Enter(HOME, K1), click(FEES)));
    }

    @Test
    void aRedirectIsFiledWhereItLandedAndNotExpandedWhenThatWasSeen() {
        URI old = site("/old/");
        URI moved = site("/new/");
        Site site = new Site().links(HOME, old).links(moved, FEES).redirect(old, moved);
        Site bare = new Site().links(HOME, old).redirect(old, moved);

        List<Visit> perPage = run(walker(CoverageMode.ONCE_PER_PAGE, SITE_ONLY)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME, moved)), site);
        List<Visit> perArrival = run(walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME, moved)), bare);

        assertThat(perPage).filteredOn(visit -> ENTERED_HOME.equals(visit.task().from())).singleElement()
                .satisfies(visit -> {
                    assertThat(visit.landing()).isEqualTo(new Landing(moved, Arrival.REDIRECTED));
                    assertThat(visit.repeats()).isEqualTo(1);
                    assertThat(visit.children()).isZero();
                });
        assertThat(perArrival).filteredOn(visit -> ENTERED_HOME.equals(visit.task().from())).singleElement()
                .satisfies(visit -> assertThat(visit.repeats()).isZero());
    }

    @Test
    void theShortestPathToAPageIsVisitedFirst() {
        URI a = site("/a/");
        URI b = site("/b/");
        Site site = new Site().links(HOME, a, b).links(a, b).links(b, FEES);

        List<Visit> done = run(walker(CoverageMode.ONCE_PER_PAGE, SITE_ONLY)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME)), site);

        assertThat(done).extracting(visit -> visit.task().path().size()).isSorted();
        assertThat(done).filteredOn(visit -> visit.landing().address().equals(b)).singleElement()
                .satisfies(visit -> assertThat(visit.task().path()).containsExactly(new Step.Enter(HOME, K1), click(b)));
        assertThat(done).filteredOn(visit -> visit.landing().address().equals(FEES)).singleElement()
                .satisfies(visit -> assertThat(visit.task().path()).hasSize(3));
    }

    @Test
    void aSingleClickStepGoesOneLinkDeepAndNoFurther() {
        Site site = new Site().links(HOME, CARDS).links(CARDS, FEES);

        Walker.Walk walk = walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY)
                .walk(profile("desktop"), tree(scenario("one-click", ENTER, CLICK)), List.of(HOME));

        assertThat(run(walk, site)).extracting(visit -> visit.landing().address()).containsExactly(HOME, CARDS);
        assertThat(walk.counters().pages().maxDepth()).isEqualTo(2);
    }

    @Test
    void theDepthCountsTheClicks() {
        List<Step> path = List.of(new Step.Enter(HOME, K1), click(CARDS), new Step.Reload(),
                new Step.Enter(CARDS, K2), click(FEES));

        StepTree.Node node = tree(ENTRY).entered();

        assertThat(new WalkTask(profile("desktop"), node, path, new Landing(CARDS, Arrival.ENTERED_NEW_KEY)).depth())
                .isEqualTo(3);
        assertThat(new WalkTask(profile("desktop"), node, path.subList(0, 1), null).depth()).isEqualTo(1);
        assertThat(new WalkTask(profile("desktop"), node, path.subList(0, 3),
                new Landing(CARDS, Arrival.CLICKED_WITHOUT_KEY)).depth()).isEqualTo(2);
    }

    @Test
    void aWalkTakesNoStepPastTheDepth() {
        URI news = site("/news/");
        URI article = site("/news/article/");
        Site site = new Site().links(HOME, news).links(news, article).links(article, FEES, CARDS);
        Walker.Walk walk = walkerWithDepth(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY, 3)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME));

        List<Visit> done = run(walk, site);

        assertThat(done).extracting(visit -> visit.landing().address()).containsExactly(HOME, news, article);
        assertThat(walk.beyondDepth()).isEqualTo(2);
        assertThat(walk.counters().pages().maxDepth()).isEqualTo(3);
    }

    @Test
    void aStepPastTheDepthDoesNotMarkItsPageSeen() {
        URI a = site("/a/");
        URI b = site("/b/");
        Site site = new Site().links(HOME, b).links(b, FEES).links(a, FEES);
        Walker.Walk walk = walkerWithDepth(CoverageMode.ONCE_PER_PAGE, SITE_ONLY, 2)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME, a));
        WalkTask home = walk.take().orElseThrow().task();
        WalkTask start = walk.take().orElseThrow().task();

        walk.visited(home, site.scan(home));
        WalkTask viaHome = walk.take().orElseThrow().task();
        walk.visited(viaHome, site.scan(viaHome));
        walk.visited(start, site.scan(start));

        assertThat(walk.beyondDepth()).isEqualTo(1);
        assertThat(walk.take()).get().satisfies(queued -> assertThat(queued.task().path()).containsExactly(
                new Step.Enter(a, K1), click(FEES)));
    }

    @Test
    void aDepthOfZeroFollowsEveryLink() {
        URI news = site("/news/");
        URI article = site("/news/article/");
        Walker.Walk walk = walkerWithDepth(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY, 0)
                .walk(profile("desktop"), tree(BROWSE), List.of(HOME));

        assertThat(run(walk, new Site().links(HOME, news).links(news, article).links(article, FEES)))
                .extracting(visit -> visit.landing().address()).containsExactly(HOME, news, article, FEES);
        assertThat(walk.beyondDepth()).isZero();
    }

    @Test
    void aPageThatWasNotReachedQueuesNothing() {
        Walker walker = walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY);

        for (Scenario scenario : List.of(REFRESH, BROWSE, KEY_REPLACED)) {
            Walker.Walk walk = walker.walk(profile("desktop"), tree(scenario), List.of(HOME));

            Landing landing = walk.failed(walk.take().orElseThrow().task());

            assertThat(landing).isEqualTo(new Landing(HOME, Arrival.ENTERED));
            assertThat(walk.take()).isEmpty();
        }
    }

    @Test
    void theShippedScenariosScanEveryDistinctPathOnce() {
        Walker.Walk walk = walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY)
                .walk(profile("desktop"), StepTree.of(SHIPPED), List.of(HOME, CARDS, FEES));

        List<Visit> done = run(walk, owners);

        assertThat(done).extracting(visit -> visit.task().path()).doesNotHaveDuplicates();
        assertThat(owners.loads).hasSameSizeAs(done);
        assertThat(done).filteredOn(visit -> visit.task().path().size() == 1).hasSize(3)
                .allSatisfy(visit -> assertThat(visit.task().node().scenarios())
                        .containsExactly("entry", "browse", "key-replaced", "reload"));
        assertThat(done).filteredOn(visit -> visit.task().path().size() == 2
                        && visit.task().path().getLast() instanceof Step.Click)
                .isNotEmpty()
                .allSatisfy(visit -> assertThat(visit.task().node().scenarios())
                        .containsExactly("browse", "key-replaced", "reload"));
        assertThat(done).filteredOn(visit -> visit.landing().arrival() == Arrival.RELOADED).isNotEmpty()
                .allSatisfy(visit -> assertThat(visit.task().node().scenarios()).containsExactly("reload"));
        assertThat(done).filteredOn(visit -> visit.expectedKey().equals(K2)).isNotEmpty()
                .allSatisfy(visit -> assertThat(visit.task().node().scenarios()).containsExactly("key-replaced"));
    }

    @ParameterizedTest
    @EnumSource(CoverageMode.class)
    void theScenariosTogetherVisitThePathsEachVisitsAlone(CoverageMode mode) {
        URI old = site("/old/");
        Site site = new Site().links(HOME, CARDS, old).links(CARDS, HOME).links(FEES, CARDS).redirect(old, FEES);
        Walker walker = walker(mode, SITE_ONLY);

        Set<List<Step>> together = paths(run(walker.walk(profile("desktop"), StepTree.of(SHIPPED), List.of(HOME, FEES)),
                site));
        Set<List<Step>> alone = new HashSet<>();
        for (Scenario scenario : SHIPPED) {
            alone.addAll(paths(run(walker.walk(profile("desktop"), tree(scenario), List.of(HOME, FEES)), site)));
        }

        assertThat(together).isEqualTo(alone);
    }

    @Test
    void aPageOneScenarioClicksToIsExpandedForAnotherThatGoesOn() {
        Site site = new Site().links(HOME, CARDS).links(CARDS, FEES);

        List<Visit> done = run(walker(CoverageMode.ONCE_PER_PAGE, SITE_ONLY).walk(profile("desktop"),
                tree(scenario("one-click", ENTER, CLICK), BROWSE), List.of(HOME)), site);

        assertThat(done).filteredOn(visit -> visit.landing().address().equals(CARDS))
                .extracting(visit -> visit.task().node().scenarios())
                .containsExactlyInAnyOrder(List.of("one-click"), List.of("browse"));
        assertThat(done).filteredOn(visit -> visit.landing().address().equals(FEES)).singleElement()
                .satisfies(visit -> assertThat(visit.task().node().scenarios()).containsExactly("browse"));
    }

    @Test
    void aPageIsVisitedBeforeAndAfterAReloadOfTheSameScenario() {
        List<Visit> done = run(walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY).walk(profile("desktop"),
                tree(scenario("reload-between", ENTER, CLICK_ANY, RELOAD, CLICK_ANY)), List.of(HOME)),
                new Site().links(HOME, CARDS));

        assertThat(done).filteredOn(visit -> visit.landing().equals(new Landing(CARDS, Arrival.CLICKED_WITHOUT_KEY)))
                .extracting(visit -> visit.task().path())
                .containsExactlyInAnyOrder(
                        List.of(new Step.Enter(HOME, K1), click(CARDS)),
                        List.of(new Step.Enter(HOME, K1), new Step.Reload(), click(CARDS)));
    }

    @Test
    void aReloadIsReloadedOnlyWhereAScenarioSaysSo() {
        Walker walker = walker(CoverageMode.ONCE_PER_ARRIVAL, SITE_ONLY);
        Site site = new Site().links(HOME, CARDS);
        List<Step> twice = List.of(new Step.Enter(HOME, K1), new Step.Reload(), new Step.Reload());

        assertThat(paths(run(walker.walk(profile("desktop"), StepTree.of(SHIPPED), List.of(HOME)), site)))
                .doesNotContain(twice);
        assertThat(paths(run(walker.walk(profile("desktop"), tree(scenario("twice", ENTER, RELOAD, RELOAD)),
                List.of(HOME)), site))).contains(twice);
    }

    private record Visit(WalkTask task, Landing landing, ReferralKey expectedKey, int repeats, int children) {
    }

    private static List<Visit> run(Walker.Walk walk, Site site) {
        List<Visit> done = new ArrayList<>();
        for (Optional<Walker.Queued> next = walk.take(); next.isPresent(); next = walk.take()) {
            WalkTask task = next.get().task();
            int repeats = walk.counters().pages().repeats();
            int queued = walk.queued();
            Landing landing = walk.visited(task, site.scan(task)).landing();
            done.add(new Visit(task, landing, Walker.expectedKey(task.path()),
                    walk.counters().pages().repeats() - repeats, walk.queued() - queued));
        }
        return done;
    }

    private static Walker walker(CoverageMode mode, Routes routes) {
        return new Walker(Coverage.of(mode), KEYS, routes, "r", 0);
    }

    private static Walker walkerWithDepth(CoverageMode mode, Routes routes, int depth) {
        return new Walker(Coverage.of(mode), KEYS, routes, "r", depth);
    }

    private static StepTree tree(Scenario... scenarios) {
        return StepTree.of(List.of(scenarios));
    }

    private static Set<List<Step>> paths(List<Visit> visits) {
        return visits.stream().map(visit -> visit.task().path()).collect(Collectors.toSet());
    }

    private static Scenario scenario(String name, StepWord... steps) {
        return new Scenario(name, List.of(steps));
    }

    private static Route route(boolean follow, List<ExpectEntry> expect, String... match) {
        return new Route(String.join(" ", match),
                List.of(match).stream().map(Fixtures::match).toList(), follow, expect);
    }

    private static URI site(String path) {
        return URI.create("https://trustee.io" + path);
    }

    private static Step.Click click(URI url) {
        return new Step.Click(link(url));
    }

    private static FoundLink link(URI url) {
        return new FoundLink(url, "a[href='" + url.getPath() + "']", url.getPath(), How.HREF, true, false);
    }

    private static final class Site {

        private final Map<URI, List<URI>> graph = new HashMap<>();
        private final Map<URI, List<FoundLink>> extra = new HashMap<>();
        private final Map<URI, URI> redirects = new HashMap<>();
        private final List<WalkTask> loads = new ArrayList<>();

        Site links(URI page, URI... targets) {
            graph.put(page, List.of(targets));
            return this;
        }

        Site redirect(URI from, URI to) {
            redirects.put(from, to);
            return this;
        }

        PageScan scan(WalkTask task) {
            loads.add(task);
            URI target = switch (task.path().getLast()) {
                case Step.Enter enter -> URI.create(enter.url() + "?r=" + enter.key());
                case Step.Click click -> click.link().url();
                case Step.Reload _ -> Objects.requireNonNull(task.from()).address();
            };
            URI page = URI.create(target.toString().replaceFirst("\\?.*", ""));
            URI landed = redirects.getOrDefault(page, target);
            URI address = redirects.getOrDefault(page, page);
            List<FoundLink> found = new ArrayList<>(graph.getOrDefault(address, List.of()).stream()
                    .map(WalkerTest::link).toList());
            found.addAll(extra.getOrDefault(address, List.of()));
            return new PageScan(landed, found, List.of(), List.of());
        }
    }
}
