package org.booklore.service.metadata.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.response.perrypediaapi.PerrypediaCategoryMembersResponse;
import org.booklore.model.dto.response.perrypediaapi.PerrypediaParseResponse;
import org.booklore.model.dto.response.perrypediaapi.PerrypediaSearchResponse;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.parser.perrypedia.InfoboxWikitextParser;
import org.booklore.util.BookUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches metadata for Perry Rhodan / Perry Rhodan Neo / Atlan Heftromane from
 * Perrypedia (https://www.perrypedia.de), a MediaWiki-based fan encyclopedia.
 * <p>
 * There is no structured query API (no Semantic MediaWiki), and the wiki's
 * API refuses anonymous requests for page content, so each article is read
 * with two plain page requests: the raw wikitext ({@code action=raw}, for
 * fields the infobox template exposes as literal parameters — title,
 * subtitle, author, issue number, publication date) and the rendered page
 * HTML. Redirects such as {@code Quelle:PR3403} are followed by hand in the
 * wikitext. The story cycle ("Zyklus") and the cover image are only
 * available as expanded template output, not literal parameters, so both
 * are read from the rendered HTML infobox instead of the wikitext.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerrypediaParser implements BookParser, DetailedMetadataProvider {

    private static final String PERRYPEDIA_BASE_URL = "https://www.perrypedia.de";
    // The MediaWiki script path is /mediawiki; the API there refuses anonymous content queries
    // (action=parse, prop=revisions), so only listing queries go through it.
    private static final String PERRYPEDIA_API_URL = PERRYPEDIA_BASE_URL + "/mediawiki/api.php";
    private static final String PERRYPEDIA_INDEX_URL = PERRYPEDIA_BASE_URL + "/mediawiki/index.php";
    private static final String WIKI_PAGE_URL = PERRYPEDIA_BASE_URL + "/wiki/";
    private static final Pattern REDIRECT_PATTERN = Pattern.compile("(?is)^\\s*#(?:WEITERLEITUNG|REDIRECT)\\s*:?\\s*\\[\\[([^\\]|#]+)");
    private static final int MAX_REDIRECT_HOPS = 3;
    // Special:FilePath is refused (403) for automated clients, so full-resolution originals are
    // reached through their direct upload URL: /mediawiki/images/<h>/<hh>/<file> is the same
    // path as the thumbnail's /mediawiki/images/thumb/<h>/<hh>/<file>/<width>px-<file>.
    private static final Pattern THUMBNAIL_PATH_PATTERN = Pattern.compile("^(.*/images)/thumb/(.+?)/[^/]+$");
    private static final String FILE_TITLE_PREFIX = "Datei:";
    private static final String USER_AGENT = "Grimmory/1.0 (Book and Comic Metadata Fetcher; +https://github.com/grimmory-tools/grimmory)";

    // Icons that can appear as <img> tags in the same infobox/section-0 HTML as the cover:
    // the disambiguation-page marker, and the reading-/audio-sample icons. Matched against
    // the <img> "alt" attribute, which mirrors the wiki file name for all of these.
    private static final Set<String> NON_COVER_IMAGE_FILENAMES = Set.of(
            "Logo_Begriffsklärung.png", "Leseprobe.png", "Hörprobe.png"
    );

    /**
     * Per-series cover gallery category and cover file naming ({@code PR0122.jpg},
     * {@code Neo001.jpg}, {@code A259 1.JPG}), keyed by {@code perrypediaId} prefix. Only the
     * name stem is fixed; suffix and extension case vary, so the actual file name is read from
     * the category listing rather than constructed.
     */
    private record CoverCategory(String title, String filenamePrefix, int issueDigits) {
    }

    private static final Map<String, CoverCategory> COVER_CATEGORIES = Map.of(
            "PR", new CoverCategory("Kategorie:Cover_-_Rhodan_Heft", "PR", 4),
            "PRN", new CoverCategory("Kategorie:Cover_-_Neo_Heft", "Neo", 3),
            "A", new CoverCategory("Kategorie:Cover_-_Atlan_Heft", "A", 3)
    );
    private static final int COVER_CATEGORY_LOOKUP_LIMIT = 5;

    // PRN must be tried before PR so "PRN389" isn't mistakenly matched as prefix "PR", number "N389".
    private static final Pattern SOURCE_ID_PATTERN = Pattern.compile("(?i)\\b(PRN|PR|A)\\s*-?\\s*(\\d{1,4})\\b");

    private static final Map<String, Integer> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", 1), Map.entry("februar", 2), Map.entry("märz", 3), Map.entry("april", 4),
            Map.entry("mai", 5), Map.entry("juni", 6), Map.entry("juli", 7), Map.entry("august", 8),
            Map.entry("september", 9), Map.entry("oktober", 10), Map.entry("november", 11), Map.entry("dezember", 12)
    );
    private static final Pattern GERMAN_DATE_PATTERN = Pattern.compile("(\\d{1,2})\\.\\s*(\\p{L}+)\\s*(\\d{4})");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AppSettingService appSettingService;

    // Conservative self-imposed rate limit — no documented Perrypedia API limit was found.
    private static final int MAX_REQUESTS_PER_WINDOW = 60;
    private static final long RATE_LIMIT_WINDOW_MS = 60_000;
    private static final long TOKEN_GENERATION_INTERVAL = RATE_LIMIT_WINDOW_MS / MAX_REQUESTS_PER_WINDOW;
    private final Object waitLock = new Object();
    private long lastTokenGenerationTime = 0;
    private long tokenCount = MAX_REQUESTS_PER_WINDOW;

    private record SourceId(String prefix, String number) {
        String key() {
            return prefix + number;
        }
    }

    @Override
    public boolean isEnabled() {
        return getSettings().map(MetadataProviderSettings.Perrypedia::isEnabled).orElse(false);
    }

    private Optional<MetadataProviderSettings.Perrypedia> getSettings() {
        var appSettings = appSettingService.getAppSettings();

        if (
                appSettings == null ||
                appSettings.getMetadataProviderSettings() == null
        ) {
            return Optional.empty();
        }

        return Optional.ofNullable(appSettings.getMetadataProviderSettings().getPerrypedia());
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        BookMetadata metadata = fetchTopMetadata(book, fetchMetadataRequest);
        return metadata != null ? List.of(metadata) : Collections.emptyList();
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        String searchText = resolveSearchText(book, fetchMetadataRequest);
        if (searchText == null) {
            log.warn("Perrypedia: no valid search term provided for metadata fetch.");
            return null;
        }

        SourceId sourceId = extractSourceId(searchText);
        if (sourceId != null) {
            BookMetadata metadata = fetchBySourceId(sourceId);
            if (metadata != null) {
                return metadata;
            }
            log.info("Perrypedia: no article found for source id '{}', falling back to title search.", sourceId.key());
        }

        return fetchBySearch(searchText);
    }

    @Override
    public BookMetadata fetchDetailedMetadata(String providerItemId) {
        if (providerItemId == null || providerItemId.isBlank()) {
            return null;
        }
        SourceId sourceId = extractSourceId(providerItemId);
        if (sourceId == null) {
            log.warn("Perrypedia: '{}' is not a recognized source id.", providerItemId);
            return null;
        }
        return fetchBySourceId(sourceId);
    }

    private String resolveSearchText(Book book, FetchMetadataRequest request) {
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            return request.getTitle();
        }
        if (book.getPrimaryFile() != null && book.getPrimaryFile().getFileName() != null && !book.getPrimaryFile().getFileName().isBlank()) {
            return BookUtils.cleanFileName(book.getPrimaryFile().getFileName());
        }
        return null;
    }

    private SourceId extractSourceId(String text) {
        Matcher matcher = SOURCE_ID_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String prefix = matcher.group(1).toUpperCase(Locale.ROOT);
        String number = String.valueOf(Integer.parseInt(matcher.group(2)));
        return new SourceId(prefix, number);
    }

    /** Resolves Quelle:&lt;prefix&gt;&lt;number&gt; through its redirect to the article, in one request. */
    private BookMetadata fetchBySourceId(SourceId sourceId) {
        try {
            waitForRateLimit();
            PerrypediaParseResponse.Parse parse = fetchPage("Quelle:" + sourceId.key());
            if (parse == null) {
                return null;
            }
            return toMetadata(parse.getWikitext(), parse.getText(), sourceId, parse.getTitle());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            log.error("Error fetching metadata from Perrypedia API for source id '{}'", sourceId.key(), e);
            return null;
        }
    }

    private BookMetadata fetchBySearch(String title) {
        try {
            waitForRateLimit();

            URI searchUri = UriComponentsBuilder.fromUriString(PERRYPEDIA_API_URL)
                    .queryParam("action", "query")
                    .queryParam("list", "search")
                    .queryParam("srnamespace", "0")
                    .queryParam("srlimit", "1")
                    .queryParam("format", "json")
                    .queryParam("formatversion", "2")
                    .queryParam("srsearch", title)
                    .encode()
                    .build()
                    .toUri();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(searchUri)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Perrypedia search API returned status code {}", response.statusCode());
                return null;
            }

            PerrypediaSearchResponse searchResponse = objectMapper.readValue(response.body(), PerrypediaSearchResponse.class);
            if (searchResponse.getQuery() == null || searchResponse.getQuery().getSearch() == null || searchResponse.getQuery().getSearch().isEmpty()) {
                return null;
            }
            String matchedTitle = searchResponse.getQuery().getSearch().getFirst().getTitle();

            PerrypediaParseResponse.Parse parse = fetchPage(matchedTitle);
            if (parse == null) {
                return null;
            }
            // Best-effort: a search-matched title rarely embeds the source id, so this is
            // usually null and toMetadata falls back to series-prefix + Nummer.
            SourceId sourceId = extractSourceId(parse.getTitle());
            return toMetadata(parse.getWikitext(), parse.getText(), sourceId, parse.getTitle());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            log.error("Error searching Perrypedia for '{}'", title, e);
            return null;
        }
    }

    /**
     * Reads an article's raw wikitext, following a {@code #WEITERLEITUNG} redirect by hand, then
     * its rendered HTML. The result reuses {@link PerrypediaParseResponse.Parse} as a plain holder.
     * Returns null when the page doesn't exist; a failed HTML request only costs the
     * HTML-derived fields (cycle, cover).
     */
    private PerrypediaParseResponse.Parse fetchPage(String pageTitle) throws IOException, InterruptedException {
        String title = pageTitle;
        String wikitext = null;
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            waitForRateLimit();
            wikitext = fetchBody(UriComponentsBuilder.fromUriString(PERRYPEDIA_INDEX_URL)
                    .queryParam("title", title)
                    .queryParam("action", "raw")
                    .encode().build().toUri());
            if (wikitext == null) {
                return null;
            }
            Matcher redirect = REDIRECT_PATTERN.matcher(wikitext);
            if (!redirect.find()) {
                break;
            }
            title = redirect.group(1).trim();
            wikitext = null;
        }
        if (wikitext == null) {
            log.error("Perrypedia: too many redirects starting at '{}'", pageTitle);
            return null;
        }

        waitForRateLimit();
        String html = fetchBody(URI.create(WIKI_PAGE_URL + UriUtils.encodePath(title.replace(' ', '_'), StandardCharsets.UTF_8)));

        PerrypediaParseResponse.Parse parse = new PerrypediaParseResponse.Parse();
        parse.setTitle(title);
        parse.setWikitext(wikitext);
        parse.setText(html);
        return parse;
    }

    private String fetchBody(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("Perrypedia request {} returned status code {}", uri, response.statusCode());
            return null;
        }
        return response.body();
    }

    private BookMetadata toMetadata(String wikitext, String html, SourceId fallbackSourceId, String resolvedTitle) {
        Optional<InfoboxWikitextParser.InfoboxBlock> blockOpt = InfoboxWikitextParser.parse(wikitext);
        if (blockOpt.isEmpty()) {
            return null;
        }
        InfoboxWikitextParser.InfoboxBlock block = blockOpt.get();
        Map<String, String> fields = block.fields();

        String seriesPrefix = detectSeriesPrefix(block.templateName());
        String number = fields.get("Nummer");
        String perrypediaId = (seriesPrefix != null && number != null)
                ? seriesPrefix + number
                : (fallbackSourceId != null ? fallbackSourceId.key() : null);

        String title = fields.get("Titel");
        if (title == null || title.isBlank()) {
            title = resolvedTitle;
        }

        Document doc = (html == null || html.isBlank()) ? null : Jsoup.parse(html);
        String coverUrl = extractCoverUrl(doc);
        if (coverUrl == null) {
            coverUrl = fetchCoverFromCategory(seriesPrefix, number);
        }

        return BookMetadata.builder()
                .provider(MetadataProvider.Perrypedia)
                .perrypediaId(perrypediaId)
                .externalUrl(perrypediaId != null ? PERRYPEDIA_BASE_URL + "/wiki/Quelle:" + perrypediaId : null)
                .title(title)
                .subtitle(fields.get("Untertitel"))
                .authors(parseAuthors(fields.get("Autor")))
                .seriesName(extractZyklus(doc))
                .seriesNumber(parseFloat(number))
                .publishedDate(parseGermanDate(fields.get("Erscheinungsdatum")))
                .language("de")
                .thumbnailUrl(coverUrl)
                .build();
    }

    /**
     * The infobox template name encodes which series an article belongs to:
     * "Roman Zyklus N" (classic), "Handlungszusammenfassung Neo Staffel N"
     * (Neo), or "Handlungszusammenfassung Atlan &lt;cycle name&gt;" (Atlan).
     * Used only to derive the {@code perrypediaId} prefix — the story cycle
     * itself ("Zyklus") comes from {@link #extractZyklus}, since the cycle
     * name isn't a literal template parameter for the classic/Neo series
     * (only Atlan happens to embed it directly in the template name).
     */
    private String detectSeriesPrefix(String templateName) {
        if (templateName == null) {
            return null;
        }
        String lower = templateName.toLowerCase(Locale.ROOT);
        if (lower.contains("neo")) {
            return "PRN";
        }
        if (lower.contains("atlan")) {
            return "A";
        }
        if (lower.startsWith("roman zyklus") || lower.startsWith("handlungszusammenfassung")) {
            return "PR";
        }
        return null;
    }

    /**
     * Reads the "Zyklus" (story cycle) row out of the rendered infobox table.
     * This can't come from the wikitext parameters — the cycle name is
     * template-expanded from the cycle number ("Roman Zyklus 42" -&gt;
     * "Mythos"), not passed as a literal argument — so this needs the
     * rendered HTML rather than {@link InfoboxWikitextParser}.
     */
    private String extractZyklus(Document doc) {
        if (doc == null) {
            return null;
        }
        for (Element row : doc.select("tr")) {
            Elements cells = row.select("td, th");
            if (cells.size() < 2) {
                continue;
            }
            String label = cells.getFirst().text().trim();
            if (label.equalsIgnoreCase("Zyklus:") || label.equalsIgnoreCase("Zyklus")) {
                String value = cells.get(1).text().trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * Reads the cover image out of the rendered infobox HTML — the same document already
     * parsed for {@link #extractZyklus}, so this needs no extra request. Filenames aren't a
     * predictable {@code <prefix><nnnn>.jpg} pattern across series (Atlan's is e.g.
     * {@code A800_1.JPG}), so this reads the actual {@code <img>} tag rather than constructing
     * one, skipping the handful of non-cover icons ({@link #NON_COVER_IMAGE_FILENAMES}) that can
     * appear in the same block.
     * <p>
     * The infobox only embeds downscaled thumbnails, so the widest {@code srcset} thumbnail's
     * URL is mapped back to the full-resolution original's upload path ({@link #originalImageUrl}).
     */
    private String extractCoverUrl(Document doc) {
        if (doc == null) {
            return null;
        }
        for (Element img : doc.select("img")) {
            String filename = img.attr("alt");
            if (NON_COVER_IMAGE_FILENAMES.stream().anyMatch(filename::equalsIgnoreCase)) {
                continue;
            }
            String url = widestImageUrl(img);
            if (url != null && !url.isBlank()) {
                return resolveImageUrl(originalImageUrl(url));
            }
        }
        return null;
    }

    /**
     * Fallback for articles whose infobox has no cover image: looks the issue up in its series'
     * cover gallery category instead. One extra request, only made when the infobox came up empty.
     */
    private String fetchCoverFromCategory(String seriesPrefix, String number) {
        CoverCategory category = seriesPrefix != null ? COVER_CATEGORIES.get(seriesPrefix) : null;
        if (category == null || number == null) {
            return null;
        }
        int issue;
        try {
            issue = Integer.parseInt(number.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String stem = category.filenamePrefix() + String.format("%0" + category.issueDigits() + "d", issue);
        // e.g. "PR0122.jpg", "Neo390.jpg", "A259 1.JPG" — optional edition suffix, any extension case.
        Pattern coverName = Pattern.compile("(?i)" + Pattern.quote(stem) + "(?:[ _]\\d+)?\\.\\p{Alpha}+");

        URI uri = UriComponentsBuilder.fromUriString(PERRYPEDIA_API_URL)
                .queryParam("action", "query")
                .queryParam("list", "categorymembers")
                .queryParam("cmtitle", category.title())
                .queryParam("cmtype", "file")
                // Category sort keys are the upper-cased file names.
                .queryParam("cmstartsortkeyprefix", stem.toUpperCase(Locale.ROOT))
                .queryParam("cmlimit", String.valueOf(COVER_CATEGORY_LOOKUP_LIMIT))
                .queryParam("format", "json")
                .queryParam("formatversion", "2")
                .encode()
                .build()
                .toUri();

        try {
            waitForRateLimit();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Perrypedia category API returned status code {}", response.statusCode());
                return null;
            }

            PerrypediaCategoryMembersResponse members = objectMapper.readValue(response.body(), PerrypediaCategoryMembersResponse.class);
            if (members.getQuery() == null || members.getQuery().getCategorymembers() == null) {
                return null;
            }
            for (PerrypediaCategoryMembersResponse.CategoryMember member : members.getQuery().getCategorymembers()) {
                String title = member.getTitle();
                if (title == null || !title.startsWith(FILE_TITLE_PREFIX)) {
                    continue;
                }
                String filename = title.substring(FILE_TITLE_PREFIX.length());
                if (coverName.matcher(filename).matches()) {
                    return fetchImageUrl(title);
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            log.error("Error looking up Perrypedia cover for '{}' in {}", stem, category.title(), e);
            return null;
        }
    }

    /** Looks up a file's direct upload URL (one extra request); {@code fileTitle} includes the {@code Datei:} prefix. */
    private String fetchImageUrl(String fileTitle) throws IOException, InterruptedException {
        waitForRateLimit();
        String body = fetchBody(UriComponentsBuilder.fromUriString(PERRYPEDIA_API_URL)
                .queryParam("action", "query")
                .queryParam("titles", fileTitle)
                .queryParam("prop", "imageinfo")
                .queryParam("iiprop", "url")
                .queryParam("format", "json")
                .queryParam("formatversion", "2")
                .encode().build().toUri());
        if (body == null) {
            return null;
        }
        JsonNode url = objectMapper.readTree(body).path("query").path("pages").path(0).path("imageinfo").path(0).path("url");
        return url.isTextual() ? url.asText() : null;
    }

    /** Picks the highest-resolution URL between an {@code <img>}'s {@code src} and {@code srcset} candidates. */
    private String widestImageUrl(Element img) {
        String best = img.attr("src");
        float bestDensity = 1.0f;
        String srcset = img.attr("srcset");
        if (srcset.isBlank()) {
            return best;
        }
        for (String candidate : srcset.split(",")) {
            String[] parts = candidate.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isBlank()) {
                continue;
            }
            float density = 1.0f;
            if (parts.length > 1 && parts[1].endsWith("x")) {
                try {
                    density = Float.parseFloat(parts[1].substring(0, parts[1].length() - 1));
                } catch (NumberFormatException ignored) {
                    // keep default density for a malformed descriptor
                }
            }
            if (density > bestDensity) {
                bestDensity = density;
                best = parts[0];
            }
        }
        return best;
    }

    /** Maps a thumbnail upload URL to its full-resolution original; any other URL is returned unchanged. */
    private String originalImageUrl(String url) {
        Matcher matcher = THUMBNAIL_PATH_PATTERN.matcher(url);
        return matcher.matches() ? matcher.group(1) + "/" + matcher.group(2) : url;
    }

    private String resolveImageUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return PERRYPEDIA_BASE_URL + url;
    }

    private List<String> parseAuthors(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private Float parseFloat(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate parseGermanDate(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw;
        int comma = s.indexOf(',');
        if (comma >= 0) {
            s = s.substring(comma + 1).trim();
        }
        Matcher matcher = GERMAN_DATE_PATTERN.matcher(s);
        if (!matcher.find()) {
            return null;
        }
        int day = Integer.parseInt(matcher.group(1));
        Integer month = GERMAN_MONTHS.get(matcher.group(2).toLowerCase(Locale.GERMAN));
        if (month == null) {
            return null;
        }
        int year = Integer.parseInt(matcher.group(3));
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private void waitForRateLimit() throws InterruptedException {
        while (true) {
            synchronized (waitLock) {
                long currentTime = System.currentTimeMillis();
                long timeSinceLastRequest = currentTime - lastTokenGenerationTime;

                if (timeSinceLastRequest > TOKEN_GENERATION_INTERVAL) {
                    long tokensToAdd = timeSinceLastRequest / TOKEN_GENERATION_INTERVAL;
                    tokenCount = Math.min(tokenCount + tokensToAdd, MAX_REQUESTS_PER_WINDOW);
                    lastTokenGenerationTime += tokensToAdd * TOKEN_GENERATION_INTERVAL;
                }

                if (tokenCount > 0) {
                    tokenCount--;
                    return;
                }
            }
            try {
                Thread.sleep(TOKEN_GENERATION_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rate limiter interrupted", e);
                throw e;
            }
        }
    }
}
