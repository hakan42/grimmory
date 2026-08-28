package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerrypediaParserTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private HttpClient httpClient;

    @InjectMocks
    private PerrypediaParser parser;

    private String readFixture(String name) throws IOException {
        String filename = "perrypedia/" + name + ".fixture";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filename)) {
            assert is != null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Builds a formatversion=2 MediaWiki query response wrapping the given wikitext, resolved via redirect. */
    private String buildQueryResponse(String redirectFrom, String resolvedTitle, String wikitext) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode query = root.putObject("query");
        if (redirectFrom != null) {
            query.putArray("redirects").addObject().put("from", redirectFrom).put("to", resolvedTitle);
        }
        var page = query.putArray("pages").addObject();
        page.put("title", resolvedTitle);
        var content = page.putArray("revisions").addObject().putObject("slots").putObject("main");
        content.put("content", wikitext);
        return root.toString();
    }

    private String buildSearchResponse(String title) {
        ObjectNode root = objectMapper.createObjectNode();
        var search = root.putObject("query").putArray("search");
        search.addObject().put("title", title);
        return root.toString();
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> getResponse(int statusCode, String payload) {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(statusCode);
        when(mockResponse.body()).thenReturn(payload);
        return mockResponse;
    }

    private void mockResponse(String uriContains, int statusCode, String payload) throws IOException, InterruptedException {
        HttpResponse<String> response = getResponse(statusCode, payload);
        when(
                httpClient.<String>send(
                        argThat(arg -> arg != null && arg.uri().toString().contains(uriContains)),
                        any()
                )
        ).thenReturn(response);
    }

    private void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void fetchTopMetadata_EmptySearchTerm_ReturnsNull() {
        setUp();

        Book book = Book.builder().build();
        FetchMetadataRequest request = FetchMetadataRequest.builder().build();

        assertThat(parser.fetchTopMetadata(book, request)).isNull();
    }

    @Test
    void fetchMetadata_EmptySearchTerm_ReturnsEmptyList() {
        setUp();

        Book book = Book.builder().build();
        FetchMetadataRequest request = FetchMetadataRequest.builder().build();

        assertThat(parser.fetchMetadata(book, request)).isEmpty();
    }

    @Test
    void fetchTopMetadata_ClassicSeries_ResolvesViaSourceId() throws Exception {
        setUp();
        mockResponse("Quelle:PR3000", 200,
                buildQueryResponse("Quelle:PR3000", "Mythos Erde (Roman)", readFixture("mythos_erde")));

        Book book = Book.builder().build();
        FetchMetadataRequest request = FetchMetadataRequest.builder().title("PR 3000 - Mythos Erde").build();

        BookMetadata metadata = parser.fetchTopMetadata(book, request);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getPerrypediaId()).isEqualTo("PR3000");
        assertThat(metadata.getTitle()).isEqualTo("Mythos Erde");
        assertThat(metadata.getSubtitle()).isEqualTo("Die Zeit verändert alles");
        assertThat(metadata.getAuthors()).containsExactly("Christian Montillon", "Wim Vandemaan");
        assertThat(metadata.getSeriesName()).isEqualTo("Perry Rhodan");
        assertThat(metadata.getSeriesNumber()).isEqualTo(3000.0f);
        assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2019, 2, 15));

        List<BookMetadata> results = parser.fetchMetadata(book, request);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getPerrypediaId()).isEqualTo("PR3000");
    }

    @Test
    void fetchTopMetadata_NeoSeries_ResolvesViaSourceId() throws Exception {
        setUp();
        mockResponse("Quelle:PRN389", 200,
                buildQueryResponse("Quelle:PRN389", "Wenn Sterne bluten", readFixture("wenn_sterne_bluten")));

        Book book = Book.builder().build();
        FetchMetadataRequest request = FetchMetadataRequest.builder().title("PRN389 - Wenn Sterne bluten").build();

        BookMetadata metadata = parser.fetchTopMetadata(book, request);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getPerrypediaId()).isEqualTo("PRN389");
        assertThat(metadata.getTitle()).isEqualTo("Wenn Sterne bluten");
        assertThat(metadata.getSeriesName()).isEqualTo("Perry Rhodan Neo");
        assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2026, 8, 14));
    }

    @Test
    void fetchTopMetadata_AtlanSeries_ResolvesViaSourceId() throws Exception {
        setUp();
        mockResponse("Quelle:A800", 200,
                buildQueryResponse("Quelle:A800", "Die Zeitfestung", readFixture("die_zeitfestung")));

        Book book = Book.builder().build();
        FetchMetadataRequest request = FetchMetadataRequest.builder().title("A800 - Die Zeitfestung").build();

        BookMetadata metadata = parser.fetchTopMetadata(book, request);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getPerrypediaId()).isEqualTo("A800");
        assertThat(metadata.getTitle()).isEqualTo("Die Zeitfestung");
        assertThat(metadata.getSubtitle()).isEqualTo("Überraschung im Intern-Kosmos");
        assertThat(metadata.getSeriesName()).isEqualTo("Atlan");
        assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(1987, 1, 27));
    }

    @Test
    void fetchTopMetadata_NoSourceId_FallsBackToTitleSearch() throws Exception {
        setUp();
        mockResponse("list=search", 200, buildSearchResponse("Wenn Sterne bluten"));
        mockResponse("titles=Wenn", 200,
                buildQueryResponse(null, "Wenn Sterne bluten", readFixture("wenn_sterne_bluten")));

        Book book = Book.builder().build();
        FetchMetadataRequest request = FetchMetadataRequest.builder().title("Wenn Sterne bluten").build();

        BookMetadata metadata = parser.fetchTopMetadata(book, request);

        assertThat(metadata).isNotNull();
        // No source id could be matched from the search-resolved title itself, but the series
        // (and thus the perrypediaId prefix) is still recovered from the infobox template name.
        assertThat(metadata.getPerrypediaId()).isEqualTo("PRN389");
        assertThat(metadata.getSeriesName()).isEqualTo("Perry Rhodan Neo");
    }
}
