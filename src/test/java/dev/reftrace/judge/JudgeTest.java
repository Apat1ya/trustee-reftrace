package dev.reftrace.judge;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.PageScan;
import dev.reftrace.browse.StoredValue;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.ExpectEntry;
import dev.reftrace.config.Expectation;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeTest {

    private static final ReferralKey K1 = ReferralKey.of("K1aaaaaaaaa");
    private static final ReferralKey K2 = ReferralKey.of("K2bbbbbbbbb");
    private static final URI PAGE = URI.create("https://trustee.io/");

    private static final Expectation.OnLink SEGMENT_1 = new Expectation.PathSegment(1);
    private static final Expectation.OnLink SEGMENT_2 = new Expectation.PathSegment(2);
    private static final Expectation.OnLink QUERY_R = new Expectation.Query("r");
    private static final Expectation.OnLink QUERY_REF = new Expectation.Query("ref");
    private static final Expectation.OnLink R_IF_PRESENT = new Expectation.QueryIfPresent("r");
    private static final Expectation.OnLink FAIL = new Expectation.Fail();

    private static final String REFERRAL = "referral-link";
    private static final String GO = "go";
    private static final String PARTNER = "partner";
    private static final String PARTNER_DEEP = "partner-deep";
    private static final String STORES = "direct-store-link";
    private static final String SHOP = "shop";
    private static final String BLOG = "blog";
    private static final String WALLET = "wallet";

    private static final Judge JUDGE = new Judge(new Routes(List.of(
            route("site", true, List.of(), "trustee.io", "*.trustee.io"),
            route(REFERRAL, false, List.of(segment(1)), "trusteeplus.app.link"),
            route(GO, false, List.of(query("r")), "go.trustee.io"),
            route(PARTNER, false, List.of(segment(2), query("ref")), "partner.test"),
            route(PARTNER_DEEP, false, List.of(query("ref")), "partner.test/deep/**"),
            route(STORES, false, List.of(failing()), "apps.apple.com", "play.google.com"),
            route("help", false, List.of(), "trusteeplus.app.link/help/**"),
            route(SHOP, true, List.of(cookie("ref"), query("r")), "shop.test"),
            route(BLOG, true, List.of(localStorage("ref")), "blog.test"),
            route(WALLET, true, List.of(queryIfPresent("r")), "wallet.test"))));

    @Test
    void aLinkCarryingTheKeyInTheExpectedSegmentPasses() {
        FoundLink link = link("https://trusteeplus.app.link/K1aaaaaaaaa");

        assertThat(judgeLink(link, K1)).containsExactly(new Check.Pass(link));
    }

    @Test
    void aLinkCarryingAnotherKeyIsAMismatchOnTheExpectationItFails() {
        FoundLink link = link("https://trusteeplus.app.link/K1aaaaaaaaa/x?utm=a#top");

        assertThat(judgeLink(link, K2)).containsExactly(Check.Mismatch.onLink(link, REFERRAL, List.of(SEGMENT_1), K2));
    }

    @Test
    void aPathTooShortForTheSegmentFailsIt() {
        FoundLink bare = link("https://trusteeplus.app.link/");

        assertThat(judgeLink(bare, K1)).containsExactly(Check.Mismatch.onLink(bare, REFERRAL, List.of(SEGMENT_1), K1));
    }

    @Test
    void aQueryParameterHasToBeTheKeyAndTheOnlyValue() {
        FoundLink carried = link("https://go.trustee.io/app?x=1&r=K1aaaaaaaaa");
        FoundLink missing = link("https://go.trustee.io/app?x=1");
        FoundLink doubled = link("https://go.trustee.io/app?r=K1aaaaaaaaa&r=K2bbbbbbbbb");

        assertThat(judgeLink(carried, K1)).containsExactly(new Check.Pass(carried));
        assertThat(judgeLink(missing, K1)).containsExactly(Check.Mismatch.onLink(missing, GO, List.of(QUERY_R), K1));
        assertThat(judgeLink(doubled, K1)).containsExactly(Check.Mismatch.onLink(doubled, GO, List.of(QUERY_R), K1));
    }

    @Test
    void aQueryIfPresentJudgesOnlyALinkThatCarriesTheParameter() {
        FoundLink carried = link("https://wallet.test/cards/?r=K1aaaaaaaaa");
        FoundLink replaced = link("https://wallet.test/?r=null&currencyCode=BTC");
        FoundLink empty = link("https://wallet.test/?r=");
        FoundLink without = link("https://wallet.test/stejking/");

        assertThat(judgeLink(carried, K1)).containsExactly(new Check.Pass(carried));
        assertThat(judgeLink(replaced, K1))
                .containsExactly(Check.Mismatch.onLink(replaced, WALLET, List.of(R_IF_PRESENT), K1));
        assertThat(Judge.found(R_IF_PRESENT, replaced.url())).isEqualTo("null");
        assertThat(judgeLink(empty, K1))
                .containsExactly(Check.Mismatch.onLink(empty, WALLET, List.of(R_IF_PRESENT), K1));
        assertThat(judgeLink(without, K1)).isEmpty();
    }

    @Test
    void everyExpectationOfTheRouteHasToHoldAndEachOneFailedIsNamed() {
        FoundLink both = link("https://partner.test/a/K1aaaaaaaaa?ref=K1aaaaaaaaa");
        FoundLink queryOnly = link("https://partner.test/a/b?ref=K1aaaaaaaaa");
        FoundLink neither = link("https://partner.test/a/b");

        assertThat(judgeLink(both, K1)).containsExactly(new Check.Pass(both));
        assertThat(judgeLink(queryOnly, K1))
                .containsExactly(Check.Mismatch.onLink(queryOnly, PARTNER, List.of(SEGMENT_2), K1));
        assertThat(judgeLink(neither, K1))
                .containsExactly(Check.Mismatch.onLink(neither, PARTNER, List.of(SEGMENT_2, QUERY_REF), K1));
    }

    @Test
    void aLinkWhereNoLinkMayGoIsAMismatchOnFail() {
        FoundLink store = link("https://apps.apple.com/app/id1634455978?r=K1aaaaaaaaa");

        assertThat(judgeLink(store, K1)).containsExactly(Check.Mismatch.onLink(store, STORES, List.of(FAIL), K1));
    }

    @Test
    void aMismatchNamesTheLastRouteThatMatchesTheLink() {
        FoundLink deep = link("https://partner.test/deep/b?ref=K2bbbbbbbbb");

        Check check = judgeLink(deep, K1).getFirst();

        assertThat(check).isEqualTo(Check.Mismatch.onLink(deep, PARTNER_DEEP, List.of(QUERY_REF), K1));
        assertThat(((Check.Mismatch) check).route()).isEqualTo(PARTNER_DEEP);
        assertThat(judgeLink(link("https://partner.test/deep/b?ref=K1aaaaaaaaa"), K1)).singleElement()
                .isInstanceOf(Check.Pass.class);
    }

    @Test
    void whatWasFoundIsTheValueWhereTheExpectationLooks() {
        URI url = URI.create("https://partner.test/K1aaaaaaaaa/b%2Fc/?ref=K2bbbbbbbbb&ref=K3&empty=&bare&x=%41");

        assertThat(Judge.found(new Expectation.PathSegment(1), url)).isEqualTo("K1aaaaaaaaa");
        assertThat(Judge.found(new Expectation.PathSegment(2), url)).isEqualTo("b%2Fc");
        assertThat(Judge.found(new Expectation.PathSegment(3), url)).isNull();
        assertThat(Judge.found(new Expectation.Query("ref"), url)).isEqualTo("K2bbbbbbbbb,K3");
        assertThat(Judge.found(new Expectation.Query("empty"), url)).isEmpty();
        assertThat(Judge.found(new Expectation.Query("bare"), url)).isEmpty();
        assertThat(Judge.found(new Expectation.Query("x"), url)).isEqualTo("%41");
        assertThat(Judge.found(new Expectation.Query("r"), url)).isNull();
        assertThat(Judge.found(new Expectation.Fail(), url)).isNull();
        assertThat(Judge.found(new Expectation.PathSegment(1), URI.create("https://partner.test"))).isNull();
    }

    @Test
    void aMismatchOnTheLinkCarriesWhatWasFoundForEachFailedExpectation() {
        FoundLink partner = link("https://partner.test/a?ref=K2bbbbbbbbb&ref=K1aaaaaaaaa");
        FoundLink store = link("https://apps.apple.com/app/id1634455978");

        assertThat(judgeLink(partner, K1)).singleElement().isInstanceOfSatisfying(Check.Mismatch.class,
                mismatch -> assertThat(mismatch.failed()).containsExactly(
                        new Check.Mismatch.Unmet(SEGMENT_2, null),
                        new Check.Mismatch.Unmet(QUERY_REF, "K2bbbbbbbbb,K1aaaaaaaaa")));
        assertThat(judgeLink(store, K1)).singleElement().isInstanceOfSatisfying(Check.Mismatch.class,
                mismatch -> assertThat(mismatch.failed()).containsExactly(new Check.Mismatch.Unmet(FAIL, null)));
    }

    @Test
    void aLinkIsNotJudgedWhenItsRouteExpectsNothingOrNoRouteMatches() {
        assertThat(judgeLink(link("https://trustee.io/ua/cards/"), K1)).isEmpty();
        assertThat(judgeLink(link("https://trusteeplus.app.link/help/faq"), K1)).isEmpty();
        assertThat(judgeLink(link("https://example.com/K1aaaaaaaaa"), K1)).isEmpty();
    }

    @Test
    void aFoundLinkIsJudgedOnlyAgainstWhatItHasToCarryItself() {
        FoundLink shop = link("https://shop.test/?r=K2bbbbbbbbb");

        assertThat(judgeLink(shop, K1)).containsExactly(
                Check.Mismatch.onLink(shop, SHOP, List.of(QUERY_R), K1));
        assertThat(judgeLink(link("https://blog.test/"), K1)).isEmpty();
    }

    @Test
    void aStoredValueHasToBeTheKeyExactly() {
        FoundLink shop = link("https://shop.test/?r=K1aaaaaaaaa");
        Expectation.Cookie cookie = new Expectation.Cookie("ref");

        assertThat(judgeStored(new StoredValue(shop, SHOP, cookie, "K1aaaaaaaaa"), K1))
                .containsExactly(new Check.Pass(shop));
        assertThat(judgeStored(new StoredValue(shop, SHOP, cookie, "k1aaaaaaaaa"), K1))
                .containsExactly(Check.Mismatch.onArrival(shop, SHOP, cookie, K1, "k1aaaaaaaaa"));
        assertThat(judgeStored(new StoredValue(shop, SHOP, cookie, "K2bbbbbbbbb"), K1))
                .containsExactly(Check.Mismatch.onArrival(shop, SHOP, cookie, K1, "K2bbbbbbbbb"));
    }

    @Test
    void nothingStoredIsAMismatchWithNothingFound() {
        FoundLink blog = link("https://blog.test/");
        Expectation.LocalStorage localStorage = new Expectation.LocalStorage("ref");

        Check check = judgeStored(new StoredValue(blog, BLOG, localStorage, null), K1).getFirst();

        assertThat(check).isEqualTo(Check.Mismatch.onArrival(blog, BLOG, localStorage, K1, null));
        assertThat(check.outcome()).isEqualTo(Outcome.MISMATCH);
    }

    @Test
    void aPageAFollowedLinkLedToIsJudgedByEveryValueItStoresForTheLink() {
        FoundLink followed = link("https://shop.test/?r=K1aaaaaaaaa");
        FoundLink app = link("https://trusteeplus.app.link/K1aaaaaaaaa");
        Expectation.Cookie cookie = new Expectation.Cookie("ref");
        Expectation.LocalStorage localStorage = new Expectation.LocalStorage("ref");
        Untested qr = new Untested.Issue(UntestedReason.QR_UNREADABLE, "div.download svg", "blurred");
        URI page = URI.create("https://shop.test/?r=K1aaaaaaaaa");
        PageScan scan = new PageScan(page, List.of(app),
                List.of(new StoredValue(followed, SHOP, cookie, null),
                        new StoredValue(followed, SHOP, localStorage, "K2bbbbbbbbb")),
                List.of(qr));

        assertThat(JUDGE.judgePage(scan, K1)).containsExactly(
                new Check.Pass(app),
                Check.Mismatch.onArrival(followed, SHOP, cookie, K1, null),
                Check.Mismatch.onArrival(followed, SHOP, localStorage, K1, "K2bbbbbbbbb"),
                new Check.NotTested(qr));
    }

    @Test
    void aScanYieldsItsJudgedLinksInOrderThenWhatCouldNotBeRead() {
        FoundLink internal = link("https://trustee.io/ua/");
        FoundLink app = link("https://trusteeplus.app.link/K1aaaaaaaaa");
        FoundLink store = link("https://play.google.com/store/apps/details?id=trustee");
        Untested qr = new Untested.Issue(UntestedReason.QR_UNREADABLE, "div.download svg", "blurred");
        URI page = URI.create("https://trustee.io/");
        PageScan scan = new PageScan(page,
                List.of(internal, app, store), List.of(), List.of(qr));

        assertThat(JUDGE.judgePage(scan, K1)).containsExactly(
                new Check.Pass(app), Check.Mismatch.onLink(store, STORES, List.of(FAIL), K1), new Check.NotTested(qr));
    }

    private static List<Check> judgeLink(FoundLink link, ReferralKey key) {
        return JUDGE.judgePage(new PageScan(PAGE, List.of(link), List.of(), List.of()), key);
    }

    private static List<Check> judgeStored(StoredValue stored, ReferralKey key) {
        return JUDGE.judgePage(new PageScan(PAGE, List.of(), List.of(stored), List.of()), key);
    }

    private static FoundLink link(String url) {
        return new FoundLink(URI.create(url), "a.link", "Download", How.HREF, true, false);
    }

    private static ExpectEntry segment(int index) {
        return new ExpectEntry(index, null, null, null, null, false);
    }

    private static ExpectEntry query(String name) {
        return new ExpectEntry(null, name, null, null, null, false);
    }

    private static ExpectEntry queryIfPresent(String name) {
        return new ExpectEntry(null, null, name, null, null, false);
    }

    private static ExpectEntry cookie(String name) {
        return new ExpectEntry(null, null, null, name, null, false);
    }

    private static ExpectEntry localStorage(String name) {
        return new ExpectEntry(null, null, null, null, name, false);
    }

    private static ExpectEntry failing() {
        return new ExpectEntry(null, null, null, null, null, true);
    }

    private static Route route(String name, boolean follow, List<ExpectEntry> expect, String... match) {
        return new Route(name, Arrays.stream(match).map(Fixtures::match).toList(), follow, expect);
    }
}
