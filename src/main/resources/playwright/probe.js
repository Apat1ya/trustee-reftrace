// Installed into every page before the site's own scripts run.
//
// It publishes window.__reftrace: the vocabulary the monitor uses to look at a page. Everything it
// knows about exits comes from the configuration injected below as REFTRACE_CONFIG, so a new kind
// of exit is a configuration change. Nothing here matches class names or any other markup detail;
// the only selectors it uses are the configured blocks a walk never clicks through.
(() => {
    if (window.top !== window || window.__reftrace) {
        return;
    }
    const CFG = REFTRACE_CONFIG;

    // ---- Routes: what a link is, decided the way the Java side decides it ----

    const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

    // A host glob as HostPattern reads it: `*` any run of characters, a port and a trailing dot ignored.
    const bareHost = (host) => String(host).trim().toLowerCase().replace(/:\d+$/, '').replace(/\.$/, '');
    const hostToRegExp = (glob) => {
        const stripped = bareHost(glob);
        return new RegExp('^' + stripped.split('*').map(escapeRegExp).join('.*') + '$');
    };

    // A path glob as LinkMatch takes it, which is all of it AntPathMatcher is given: `*` within one
    // segment, and a closing `/**` for the rest, none of it included.
    const ANY_PATH = '/**';
    const pathToRegExp = (glob) => {
        const rest = String(glob).endsWith(ANY_PATH);
        const head = rest ? String(glob).slice(0, -ANY_PATH.length) : String(glob);
        return new RegExp('^' + head.split('*').map(escapeRegExp).join('[^/]*') + (rest ? '(?:/.*)?' : '') + '$');
    };

    // The path a route is matched against, read as the Java side reads it: decoded, as URI.getPath()
    // gives it, with empty segments dropped, as AntPathMatcher drops them. Null for an escape that
    // does not decode, which the Java side cannot read as a URL either.
    const routePath = (parsed) => {
        try {
            return decodeURIComponent(parsed.pathname || '/').replace(/\/{2,}/g, '/');
        } catch (e) {
            return null;
        }
    };

    // The `routes` in their order, one entry per match; the last one that matches decides, the
    // same lookup as Routes.routeFor on the Java side.
    const routes = (CFG.routes || []).map(route => ({
        host: hostToRegExp(route.host),
        path: pathToRegExp(route.path),
        wholeHost: route.path === ANY_PATH,
        follow: !!route.follow
    }));
    const exitSuffixes = (CFG.exitPathSuffixes || []).map(suffix => String(suffix).toLowerCase());
    const avoid = new RegExp(CFG.avoidLabels, 'i');
    const unwalkedBlocks = (CFG.unwalkedBlocks || []).join(', ');

    // Addresses are taken apart with plain string work. The page's own URL and URLSearchParams are
    // not used on purpose: they are ordinary properties of the window, a page can replace them, and
    // a probe that trusts them can be told whatever the page likes.
    const URL_PARTS = /^(?:([a-zA-Z][a-zA-Z0-9+.\-]*):)?(?:\/\/([^\/?#]*))?([^?#]*)(\?[^#]*)?/;

    const parse = (url) => {
        const match = URL_PARTS.exec(String(url === null || url === undefined ? '' : url).trim());
        if (!match) {
            return null;
        }
        let authority = match[2] || '';
        const at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.slice(at + 1);
        }
        const colon = authority.lastIndexOf(':');
        const host = colon >= 0 && authority.indexOf(']') < colon ? authority.slice(0, colon) : authority;
        return {
            scheme: (match[1] || '').toLowerCase(),
            hostname: host.toLowerCase(),
            pathname: match[3] || '',
            search: match[4] || ''
        };
    };

    const routeFor = (parsed) => {
        if (!parsed || !parsed.hostname) {
            return null;
        }
        const path = routePath(parsed);
        if (path === null) {
            return null;
        }
        const host = bareHost(parsed.hostname);
        for (let index = routes.length - 1; index >= 0; index--) {
            if (routes[index].host.test(host) && routes[index].path.test(path)) {
                return routes[index];
            }
        }
        return null;
    };

    // An exit is a link the walk does not follow: its last matching route says so, or it downloads the
    // installer, which is never fetched from any host. Recognised by destination only.
    //
    // A whole host that is not followed and a path are kept apart, because they say different things:
    // a link to a store host is something the site builds once its data is in, while a path-matched
    // exit — the file download, the sticky banner's keyless fallback (t.ki/banner*), a switched-off
    // part of the site — can be in the markup from the first paint, before the key is read. Only the
    // first kind tells the render phases apart, so isExitHostUrl() has its own name and is not folded
    // into isExitUrl(). The match that decides is the one routeFor() found, the last to match.
    const isExitHostUrl = (url) => {
        const route = routeFor(parse(url));
        return !!route && !route.follow && route.wholeHost;
    };

    // Any link a route does not follow counts, a switched-off part of the site as much as a store: what
    // the walk never follows is what reveal looks for and what the settle signature watches. The
    // same test as UrlPatterns.isExit on the Java side.
    const isExitUrl = (url) => {
        const parsed = parse(url);
        const path = parsed && routePath(parsed);
        if (path === null || path === undefined) {
            return false;
        }
        const route = routeFor(parsed);
        if (route && !route.follow) {
            return true;
        }
        return exitSuffixes.some(suffix => path.toLowerCase().endsWith(suffix));
    };

    // ---- DOM observations: visibility, labels, selectors and the anchors of the document ----

    // Whether the page shows the element to this visitor at all, which is what makes a link a step a
    // walk may take. It is shown when it is laid out with a box of its own, width and height above
    // zero, and neither it nor anything it sits in is hidden by style: no `display: none`, no
    // `visibility: hidden`, no `opacity: 0`, no `content-visibility: hidden` (a closed <details>).
    // Where it is does not matter: a link parked beside the screen in a closed drawer, clipped to
    // nothing by a collapsed answer, lying under a dialog or still moving is shown, because a visitor
    // gets to it by opening, scrolling or waiting. What a style takes out of the layout is not shown:
    // mostly the copy of the page meant for another screen size, and also a submenu that a click on
    // its arrow would lay out.
    const visible = (element) => {
        try {
            const rect = element.getBoundingClientRect();
            if (!rect.width || !rect.height) {
                return false;
            }
            const style = getComputedStyle(element);
            if (style.visibility === 'hidden' || style.display === 'none' || parseFloat(style.opacity) === 0) {
                return false;
            }
            if (typeof element.checkVisibility === 'function') {
                return element.checkVisibility({checkOpacity: true, checkVisibilityCSS: true});
            }
            return true;
        } catch (e) {
            return false;
        }
    };

    const labelOf = (element) => {
        let text = element.getAttribute('aria-label') || element.innerText || element.textContent || '';
        if (!text.trim()) {
            const image = element.tagName === 'IMG' ? element : element.querySelector('img[alt]');
            text = (image && image.getAttribute('alt')) || element.getAttribute('title') || '';
        }
        return text.replace(/\s+/g, ' ').trim().slice(0, 120);
    };

    const allAnchors = () => Array.prototype.slice.call(document.querySelectorAll('a[href]'));

    // Whether the link sits inside one of the configured blocks a walk never clicks through, the
    // language switcher for one. A selector the page cannot read is a configuration error, and
    // reading the page fails on it rather than let the walk click what it should not.
    const unwalked = (anchor) => !!unwalkedBlocks && anchor.closest(unwalkedBlocks) !== null;

    // A CSS selector that matches this element and nothing else in the document. It is what a report
    // names the element by and what finds it again after a fresh load, so it is built from the
    // markup and never from the position in a snapshot: an id when that id is unique, otherwise the
    // tag and classes, then its position among same-tag siblings only where they are needed,
    // walking up until the whole path is unique.
    const escape = (text) => (window.CSS && CSS.escape) ? CSS.escape(text) : String(text).replace(/[^\w-]/g, '\\$&');

    const unique = (selector, element) => {
        try {
            const found = document.querySelectorAll(selector);
            return found.length === 1 && found[0] === element;
        } catch (e) {
            return false;
        }
    };

    const classesOf = (element) => (element.getAttribute('class') || '').trim().split(/\s+/)
        .filter(name => name)
        .slice(0, 2)
        .map(name => '.' + escape(name))
        .join('');

    const positionOf = (element) => {
        let position = 1;
        for (let sibling = element.previousElementSibling; sibling; sibling = sibling.previousElementSibling) {
            if (sibling.tagName === element.tagName) {
                position++;
            }
        }
        return position;
    };

    const selectorOf = (element) => {
        const parts = [];
        for (let node = element; node && node.nodeType === 1; node = node.parentElement) {
            const tag = node.tagName.toLowerCase();
            if (node.id && unique('#' + escape(node.id), node)) {
                parts.unshift('#' + escape(node.id));
                break;
            }
            let part = tag + classesOf(node);
            if (unique([part].concat(parts).join(' > '), element)) {
                parts.unshift(part);
                break;
            }
            if (node.parentElement && node.parentElement.children.length > 1) {
                part = part + ':nth-of-type(' + positionOf(node) + ')';
            }
            parts.unshift(part);
            if (unique(parts.join(' > '), element) || tag === 'html') {
                break;
            }
        }
        return parts.join(' > ');
    };

    // The element a selector names now, for following a link found on an earlier load of the page.
    // When the selector finds nothing that still leads where the link did, a visible anchor with the
    // same address and the same text stands in for it. A hidden one never does: the click reaches it
    // all the same, and the walk would go on from a link this visitor cannot see, the copy of the
    // navigation another screen size shows. Nor does one inside a block a walk never clicks through.
    // The answer is a fresh selector, or null.
    const relocate = (options) => {
        let element = null;
        try {
            const found = document.querySelectorAll(options.selector);
            element = found.length === 1 ? found[0] : null;
        } catch (e) {
            element = null;
        }
        if (element && (!options.href || element.href === options.href)) {
            return selectorOf(element);
        }
        if (!options.href) {
            return null;
        }
        const chosen = allAnchors().find(anchor => anchor.href === options.href
            && (!options.text || labelOf(anchor) === options.text) && !unwalked(anchor) && visible(anchor));
        return chosen ? selectorOf(chosen) : null;
    };

    const describe = (anchor, index) => ({
        locator: 'a#' + index,
        selector: selectorOf(anchor),
        href: anchor.href,
        label: labelOf(anchor),
        visible: visible(anchor),
        unwalked: unwalked(anchor)
    });

    const anchors = () => allAnchors().map(describe);

    const exitAnchors = () => {
        const found = [];
        allAnchors().forEach((anchor, index) => {
            if (isExitUrl(anchor.href)) {
                found.push(describe(anchor, index));
            }
        });
        return found;
    };

    // ---- QR candidates ----

    // QR codes are recognised by the markup a QR library produces, never by class names. Strong: a
    // square svg whose clipPath holds one child per module, or with a long path made only of M/h/v/z
    // segments. Weak: a square img or canvas of plausible size, worth a decode attempt and nothing more.
    const QR_SELECTOR = 'svg, canvas, img';
    const QR_MIN_WEAK_SIZE = 80;
    const QR_MIN_CLIP_CHILDREN = 50;

    const isQrMarkup = (svg, width, height) => {
        const square = width > 0 && Math.abs(width - height) <= Math.max(2, width * 0.02);
        const gridPath = Array.prototype.some.call(svg.querySelectorAll('path'), path => {
            const d = path.getAttribute('d') || '';
            return d.length > 800 && /^[MmHhVvZz0-9 .,-]+$/.test(d);
        });
        return square && (svg.querySelectorAll('clipPath > *').length >= QR_MIN_CLIP_CHILDREN || gridPath);
    };

    const qrCodes = () => {
        const found = [];
        document.querySelectorAll(QR_SELECTOR).forEach((element, index) => {
            const tag = element.tagName.toLowerCase();
            const rect = element.getBoundingClientRect();
            let strong = false;
            let size = rect.width;
            if (tag === 'svg') {
                if (element.parentElement && element.parentElement.closest('svg')) {
                    return;
                }
                const box = (element.getAttribute('viewBox') || '').trim().split(/[\s,]+/).map(Number);
                const width = parseFloat(element.getAttribute('width')) || (box.length === 4 ? box[2] : 0) || rect.width;
                const height = parseFloat(element.getAttribute('height')) || (box.length === 4 ? box[3] : 0) || rect.height;
                if (!isQrMarkup(element, width, height)) {
                    return;
                }
                strong = true;
                size = width;
            } else if (rect.width < QR_MIN_WEAK_SIZE || Math.abs(rect.width - rect.height) > Math.max(2, rect.width * 0.05)) {
                return;
            }
            const label = (element.getAttribute('alt') || element.getAttribute('aria-label') || '').trim().slice(0, 40);
            found.push({
                locator: 'qr#' + index,
                selector: selectorOf(element),
                strong: strong,
                visible: visible(element),
                description: tag + ' ' + Math.round(size) + 'px' + (element.id ? ' #' + element.id : '')
                    + (label ? ' "' + label + '"' : '')
            });
        });
        return found;
    };

    // ---- Readiness: what tells a finished render from the first one ----

    const exitSignature = () => allAnchors()
        .map(anchor => anchor.href)
        .filter(isExitUrl)
        .sort()
        .join('|');

    // Phase-two marker: the site renders the links that lead to the stores well after the first
    // paint, so a page showing one of them has got past the first render and is worth reading.
    // Deliberately blind to path-matched exits: the file download is already there in phase one,
    // and counting it here is what let a reading happen in the gap between the two renders.
    const exitOnShow = () => allAnchors().some(anchor => isExitHostUrl(anchor.href) && visible(anchor));

    // The same marker for a page whose way towards the app is a QR code alone. Only markup a QR
    // library draws counts: it is built from the same data as the store buttons, while a square
    // picture proves nothing.
    const qrOnShow = () => qrCodes().some(candidate => candidate.strong && candidate.visible);

    const hiddenExitCount = () => {
        let hidden = 0;
        for (const anchor of allAnchors()) {
            if (isExitUrl(anchor.href) && !visible(anchor)) {
                hidden++;
            }
        }
        return hidden;
    };

    // ---- Outlining the element a screenshot is about ----

    // Marks the element a report is about, so the screenshot taken next shows which one it was.
    // The previous mark is always taken off first: two outlines would say two things at once.
    // The element is named by a selector; the answer is whether it was found and marked.
    const elementNamed = (selector) => {
        try {
            const found = document.querySelectorAll(String(selector));
            return found.length === 1 ? found[0] : null;
        } catch (e) {
            return null;
        }
    };

    const outline = (selector) => {
        unoutline();
        const anchor = elementNamed(selector);
        if (!anchor) {
            return false;
        }
        reftrace.marked = {element: anchor, style: anchor.getAttribute('style')};
        anchor.style.setProperty('outline', '3px solid #ff0000', 'important');
        anchor.style.setProperty('outline-offset', '2px', 'important');
        try {
            anchor.scrollIntoView({block: 'center', inline: 'center'});
        } catch (e) {
            // An element that refuses to scroll is still worth photographing where it is.
        }
        return true;
    };

    const unoutline = () => {
        const marked = reftrace.marked;
        reftrace.marked = null;
        if (!marked) {
            return;
        }
        try {
            if (marked.style === null) {
                marked.element.removeAttribute('style');
            } else {
                marked.element.setAttribute('style', marked.style);
            }
        } catch (e) {
            // The page replaced the element while we were looking at it; nothing left to restore.
        }
    };

    // ---- Reveal candidates, reach and menus ----

    // Elements worth touching in the hope of uncovering an exit.
    //
    // Only elements that already hold an out-of-sight exit somewhere below them qualify: the page
    // is asked, cheaply, where the hidden exits are, and the search walks up from those. Touching
    // every button on a page would cost far more and change far more than it is worth.
    //
    // Hovering is always the first move. Clicking is offered only for a disclosure control — a
    // button, a summary, something that says it expands — because clicking a plain container could
    // land on one of the links inside it and leave the page.
    //
    // Anything whose wording suggests consent, payment or signing in is left alone: a monitor looks
    // at a site, it does not act on it.
    const DISCLOSURE = 'button, summary, [role="button"], [role="menuitem"], [role="tab"],'
        + ' [aria-haspopup], [aria-expanded], [aria-controls]';

    const usable = (element) => {
        if (!element || element.nodeType !== 1 || element.closest('a[href]') || element.closest('form')
            || !visible(element)) {
            return false;
        }
        const label = labelOf(element);
        if (label && avoid.test(label)) {
            return false;
        }
        const viewportWidth = window.innerWidth || document.documentElement.clientWidth;
        const viewportHeight = window.innerHeight || document.documentElement.clientHeight;
        const rect = element.getBoundingClientRect();
        return !(rect.width >= viewportWidth * 0.95 && rect.height >= viewportHeight * 0.95);
    };

    const reactsToPointer = (element) => {
        if (element.matches(DISCLOSURE) || element.hasAttribute('onclick')) {
            return true;
        }
        const parent = element.parentElement;
        return getComputedStyle(element).cursor === 'pointer'
            && (!parent || getComputedStyle(parent).cursor !== 'pointer');
    };

    const hiddenExitsUnder = (element) => {
        let hidden = 0;
        for (const link of element.querySelectorAll('a[href]')) {
            if (isExitUrl(link.href) && !visible(link)) {
                hidden++;
            }
        }
        return hidden;
    };

    const offer = (element, kind, hiddenExits, found, offered, max) => {
        if (offered.has(element) || found.length >= max) {
            return;
        }
        offered.add(element);
        reftrace.candidates.push(element);
        found.push({
            index: reftrace.candidates.length - 1,
            kind: kind,
            label: labelOf(element).slice(0, 60),
            tag: element.tagName,
            hiddenExits: hiddenExits
        });
    };

    const revealCandidates = (options) => {
        const max = options && options.max ? options.max : 24;
        const found = [];
        const offered = new Set();
        reftrace.candidates = [];
        const seen = new Set();
        const hidden = allAnchors().filter(anchor => isExitUrl(anchor.href) && !visible(anchor));
        for (const anchor of hidden) {
            let element = anchor.parentElement;
            while (element && element.nodeType === 1 && element !== document.body) {
                if (found.length >= max || seen.has(element)) {
                    break;
                }
                seen.add(element);
                if (usable(element)) {
                    const hiddenExits = hiddenExitsUnder(element);
                    if (hiddenExits > 0) {
                        if (reactsToPointer(element)) {
                            offer(element, 'hover', hiddenExits, found, offered, max);
                        }
                        // The control that opens a drawer usually sits beside what it opens, not
                        // above it: a toggle holding no link of its own is safe to click, because
                        // there is nothing inside it that could carry the page away.
                        for (const control of element.querySelectorAll(DISCLOSURE)) {
                            if (control.querySelector('a[href]') === null && usable(control)) {
                                offer(control, 'click', hiddenExits, found, offered, max);
                            }
                        }
                    }
                }
                element = element.parentElement;
            }
        }
        return found;
    };

    // A link a click can reach: shown, and not parked beside the screen the way a closed drawer is,
    // which no scrolling brings back. Anything else a click scrolls to, so the link is scrolled to
    // first, the way the click would.
    const onScreen = (element) => {
        if (!visible(element)) {
            return false;
        }
        const rect = element.getBoundingClientRect();
        const viewportWidth = window.innerWidth || document.documentElement.clientWidth;
        return rect.right > 0 && rect.left < viewportWidth;
    };

    // Whether the element is on screen and has come to rest: it stands where it stood when this was
    // last asked. A menu slides in, and a visitor presses a link in it once it is there, not on its
    // way; asked every few dozen milliseconds, this turns true once the sliding is over.
    const places = new WeakMap();
    const restingOnScreen = (element) => {
        const rect = element.getBoundingClientRect();
        const place = [rect.left, rect.top, rect.width, rect.height].join(',');
        const before = places.get(element);
        places.set(element, place);
        return before === place && onScreen(element);
    };

    const withinReach = (element) => {
        if (!visible(element)) {
            return false;
        }
        element.scrollIntoView({block: 'nearest', inline: 'nearest'});
        return onScreen(element);
    };

    // The element as a report names what lies over a link: its opening tag, with an ellipsis for
    // whatever it holds, the way Playwright prints an element that intercepts the pointer.
    const tagOf = (element) => {
        const html = element.cloneNode(false).outerHTML;
        const end = '</' + element.tagName.toLowerCase() + '>';
        return element.childNodes.length && html.endsWith('>' + end) ? html.slice(0, -end.length) + '…' + end : html;
    };

    // Scrolls the element to the middle of the screen, at once and not smoothly, whatever scrolling
    // the page asks for: where a visitor has a link when pressing it. An element out of the layout
    // stays where it is.
    const toMiddle = (element) => {
        element.scrollIntoView({block: 'center', behavior: 'instant'});
    };

    // Whether a visitor could press the element: once it is scrolled to the middle of the screen, at
    // once and not smoothly, the point at its centre has to be the element or something inside it.
    // Anything lying over it, clipping it or letting the pointer through it answers otherwise. The
    // answer says what kept the visitor out, with the words of Playwright's call log: `not-visible`,
    // `outside-viewport` for a point no scrolling brings onto the screen, `intercepted` with the
    // element that took the point; no blocker when the element can be pressed.
    const reach = (element) => {
        toMiddle(element);
        const rect = element.getBoundingClientRect();
        if (!rect.width || !rect.height) {
            return {blocker: 'not-visible', interceptor: null};
        }
        const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
        if (hit !== null && (hit === element || element.contains(hit))) {
            return {blocker: null, interceptor: null};
        }
        if (!visible(element)) {
            return {blocker: 'not-visible', interceptor: null};
        }
        if (hit === null) {
            return {blocker: 'outside-viewport', interceptor: null};
        }
        return {blocker: 'intercepted', interceptor: tagOf(hit)};
    };

    // Where the element stands on screen, in CSS pixels of the viewport, once a visitor has brought it
    // into full view: scrolled to the middle of the screen, at once and not smoothly, unless it is in
    // full view already. A picture of the screen clipped to that box is a picture of the element.
    const inView = (element) => {
        const inside = (rect) => rect.top >= 0 && rect.left >= 0
            && rect.bottom <= window.innerHeight && rect.right <= window.innerWidth;
        if (!inside(element.getBoundingClientRect())) {
            element.scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'});
        }
        const rect = element.getBoundingClientRect();
        return {x: rect.left, y: rect.top, width: rect.width, height: rect.height};
    };

    // The controls of the configured menus, offered one menu at a time: `number` is the menu's place
    // in the configuration. The first one starts a fresh offer.
    const offerMenu = (elements, number) => {
        if (number === 0) {
            reftrace.menuControls = [];
        }
        for (const element of elements) {
            reftrace.menuControls.push({element: element, number: number});
        }
        return elements.length;
    };

    // The menu control to open next on the way to `target`: of the offered controls that are on
    // screen and not yet touched on this page, the closest one before the target, because a menu's
    // toggle comes before what it opens. Touching one twice would close again what it opened. The
    // answer is the menu's number, with the control left as candidate 0; null when none is left.
    const menuControl = (target) => {
        let chosen = null;
        for (const control of reftrace.menuControls) {
            const element = control.element;
            if (reftrace.touched.has(element) || !onScreen(element)
                || !(element.compareDocumentPosition(target) & Node.DOCUMENT_POSITION_FOLLOWING)) {
                continue;
            }
            if (chosen === null
                || chosen.element.compareDocumentPosition(element) & Node.DOCUMENT_POSITION_FOLLOWING) {
                chosen = control;
            }
        }
        reftrace.menuControls = [];
        if (chosen === null) {
            return null;
        }
        reftrace.touched.add(chosen.element);
        reftrace.candidates = [chosen.element];
        return chosen.number;
    };

    // ---- window.__reftrace: the API the monitor calls, and the settle clock ----

    const reftrace = {
        signature: null,
        since: 0,
        changes: 0,
        loaded: false,
        loadedAt: null,
        candidates: [],
        menuControls: [],
        touched: new WeakSet(),
        marked: null,
        parse: parse,
        isExitUrl: isExitUrl,
        isExitHostUrl: isExitHostUrl,
        visible: visible,
        labelOf: labelOf,
        anchors: anchors,
        exitAnchors: exitAnchors,
        qrCodes: qrCodes,
        exitSignature: exitSignature,
        exitOnShow: exitOnShow,
        qrOnShow: qrOnShow,
        selectorOf: selectorOf,
        relocate: relocate,
        hiddenExitCount: hiddenExitCount,
        outline: outline,
        unoutline: unoutline,
        revealCandidates: revealCandidates,
        onScreen: onScreen,
        restingOnScreen: restingOnScreen,
        withinReach: withinReach,
        toMiddle: toMiddle,
        reach: reach,
        inView: inView,
        offerMenu: offerMenu,
        menuControl: menuControl
    };

    reftrace.tick = () => {
        const signature = exitSignature();
        if (signature !== reftrace.signature) {
            reftrace.signature = signature;
            reftrace.since = performance.now();
            reftrace.changes++;
        }
        return reftrace.since;
    };

    // Starts the quiet window again: the driver calls it once the site's router has passed. Some
    // pages show a store link already in phase one, and their exits then stand still for a second
    // and more before the router pass; counted from before it, the quiet window would be over the
    // moment the router passes, a few dozen milliseconds before phase two rewrites the links.
    reftrace.restartQuiet = () => {
        reftrace.tick();
        reftrace.since = performance.now();
    };

    // A page has settled when it has loaded, the set of exit links has stopped moving (since the
    // router pass at the earliest, see restartQuiet), and it shows a marker of the finished render:
    // a visible link to a store, or a QR code on screen. The quiet window alone is not enough: the
    // store buttons appear more than a second after the first exits do, and on a wide viewport they
    // show the right value for about a tenth of a second before flipping. `requireMarker: false` asks for the quiet window only; the driver asks that once the
    // settle maximum is reached, so a page with no way towards the app at all still gets read.
    reftrace.settled = (options) => {
        if (!reftrace.loaded) {
            return false;
        }
        reftrace.tick();
        const quiet = performance.now() - reftrace.since;
        if (quiet < options.quietMs) {
            return false;
        }
        // Whether the links carry the key is what the page is judged on, so it is never part of
        // deciding when to look: waiting for a decorated link would never end on a page that lost it.
        return options.requireMarker === false || exitOnShow() || qrOnShow();
    };

    reftrace.state = () => ({
        loaded: reftrace.loaded,
        loadedAt: reftrace.loadedAt,
        changes: reftrace.changes,
        quietMs: Math.round(performance.now() - reftrace.since),
        exitCount: exitAnchors().length,
        hiddenExits: hiddenExitCount(),
        exitOnShow: exitOnShow(),
        qrOnShow: qrOnShow()
    });

    window.__reftrace = reftrace;
    reftrace.tick();
    setInterval(reftrace.tick, 50);

    const markLoaded = () => {
        if (!reftrace.loaded) {
            reftrace.loaded = true;
            reftrace.loadedAt = performance.now();
        }
    };
    if (document.readyState === 'complete') {
        markLoaded();
    }
    window.addEventListener('load', markLoaded);
})();
