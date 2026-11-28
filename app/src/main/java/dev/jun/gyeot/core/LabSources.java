package dev.jun.gyeot.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 문헌 조사's sources: the open scholarly APIs that need no key (arXiv, PubMed, OpenAlex, Semantic Scholar), their search
 * addresses and what their answers are read into (LabBook.Source). LabRunner fetches; this only builds and reads.
 */
public final class LabSources {
    private LabSources() {}
    public static final int PER_QUERY = 5;
    static String q(String s) { return URLEncoder.encode(s == null ? "" : s.strip(), StandardCharsets.UTF_8); }

    public static String name(String source) {
        return switch (source) { case "arxiv" -> "arXiv"; case "pubmed" -> "PubMed"; case "openalex" -> "OpenAlex"; case "semantic" -> "Semantic Scholar"; default -> source; };
    }
    /** The search address for one query on one source. */
    public static String url(String source, String query) {
        return switch (source) {
            case "arxiv" -> "https://export.arxiv.org/api/query?search_query=" + q("all:" + query.strip().replaceAll("\\s+", " AND all:")) + "&start=0&max_results=" + PER_QUERY + "&sortBy=relevance";
            case "pubmed" -> "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&retmode=json&sort=relevance&retmax=" + PER_QUERY + "&term=" + q(query);
            case "semantic" -> "https://api.semanticscholar.org/graph/v1/paper/search?limit=" + PER_QUERY + "&fields=title,abstract,year,authors,venue,url,externalIds&query=" + q(query);
            default -> "https://api.openalex.org/works?per-page=" + PER_QUERY + "&select=id,doi,title,publication_year,authorships,primary_location,abstract_inverted_index&search=" + q(query);
        };
    }
    /** PubMed's second step: the abstracts of the ids its search gave. */
    public static String pubmedFetch(List<String> ids) { return "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pubmed&retmode=xml&rettype=abstract&id=" + q(String.join(",", ids)); }

    // ── arXiv (Atom) ─────────────────────────────────────────────────

    private static final Pattern ENTRY = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
    public static List<LabBook.Source> arxiv(String xml) {
        List<LabBook.Source> out = new ArrayList<>(); if (xml == null) return out;
        Matcher m = ENTRY.matcher(xml);
        while (m.find()) {
            String e = m.group(1);
            List<String> names = all(e, "<name>(.*?)</name>");
            String id = tag(e, "id"), published = tag(e, "published");
            String category = attr(e, "arxiv:primary_category", "term");
            out.add(new LabBook.Source("", text(tag(e, "title")), authors(names), published.length() >= 4 ? published.substring(0, 4) : "",
                "arXiv" + (category.isEmpty() ? "" : " " + category), id.replace("http://", "https://"), text(tag(e, "summary")), "arxiv"));
        }
        return out;
    }

    // ── PubMed ───────────────────────────────────────────────────────

    public static List<String> pubmedIds(String json) {
        List<String> out = new ArrayList<>();
        try { JSONArray ids = new JSONObject(json).getJSONObject("esearchresult").getJSONArray("idlist"); for (int i = 0; i < ids.length(); i++) out.add(ids.getString(i)); } catch (Exception ignored) {}
        return out;
    }
    private static final Pattern ARTICLE = Pattern.compile("<PubmedArticle>(.*?)</PubmedArticle>", Pattern.DOTALL);
    public static List<LabBook.Source> pubmed(String xml) {
        List<LabBook.Source> out = new ArrayList<>(); if (xml == null) return out;
        Matcher m = ARTICLE.matcher(xml);
        while (m.find()) {
            String a = m.group(1);
            String pmid = text(first(a, "<PMID[^>]*>(.*?)</PMID>"));
            List<String> names = new ArrayList<>();
            Matcher au = Pattern.compile("<Author[^>]*>(.*?)</Author>", Pattern.DOTALL).matcher(a);
            while (au.find()) { String last = text(first(au.group(1), "<LastName>(.*?)</LastName>")), fore = text(first(au.group(1), "<Initials>(.*?)</Initials>")); if (!last.isEmpty()) names.add(last + (fore.isEmpty() ? "" : " " + fore)); }
            String abs = String.join(" ", all(a, "<AbstractText[^>]*>(.*?)</AbstractText>"));
            String year = text(first(a, "<PubDate>.*?<Year>(\\d{4})</Year>"));
            if (year.isEmpty()) { String medline = text(first(a, "<MedlineDate>(\\d{4})")); year = medline; }
            out.add(new LabBook.Source("", text(first(a, "<ArticleTitle[^>]*>(.*?)</ArticleTitle>")), authors(names), year,
                text(first(a, "<Title>(.*?)</Title>")), pmid.isEmpty() ? "" : "https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/", text(abs), "pubmed"));
        }
        return out;
    }

    // ── OpenAlex ─────────────────────────────────────────────────────

    public static List<LabBook.Source> openAlex(String json) {
        List<LabBook.Source> out = new ArrayList<>();
        try {
            JSONArray results = new JSONObject(json).getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                JSONObject w = results.getJSONObject(i);
                List<String> names = new ArrayList<>(); JSONArray au = w.optJSONArray("authorships");
                if (au != null) for (int j = 0; j < au.length(); j++) { JSONObject a = au.optJSONObject(j); JSONObject who = a == null ? null : a.optJSONObject("author"); if (who != null) names.add(who.optString("display_name")); }
                JSONObject loc = w.optJSONObject("primary_location"); JSONObject src = loc == null ? null : loc.optJSONObject("source");
                String doi = w.optString("doi", ""); if ("null".equals(doi)) doi = "";
                String url = !doi.isEmpty() ? doi : loc != null && !loc.isNull("landing_page_url") ? loc.optString("landing_page_url") : w.optString("id");
                out.add(new LabBook.Source("", w.isNull("title") ? "" : w.optString("title"), authors(names), w.isNull("publication_year") ? "" : String.valueOf(w.optInt("publication_year")),
                    src == null || src.isNull("display_name") ? "" : src.optString("display_name"), url, invert(w.optJSONObject("abstract_inverted_index")), "openalex"));
            }
        } catch (Exception ignored) {}
        return out;
    }
    /** OpenAlex keeps an abstract as word → positions; this puts the words back in order. */
    static String invert(JSONObject index) {
        if (index == null) return "";
        Map<Integer, String> at = new TreeMap<>();
        for (java.util.Iterator<String> k = index.keys(); k.hasNext();) {
            String word = k.next(); JSONArray where = index.optJSONArray(word); if (where == null) continue;
            for (int i = 0; i < where.length(); i++) at.put(where.optInt(i), word);
        }
        return String.join(" ", at.values());
    }

    // ── Semantic Scholar ─────────────────────────────────────────────

    public static List<LabBook.Source> semantic(String json) {
        List<LabBook.Source> out = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(json).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                JSONObject p = data.getJSONObject(i);
                List<String> names = new ArrayList<>(); JSONArray au = p.optJSONArray("authors");
                if (au != null) for (int j = 0; j < au.length(); j++) { JSONObject a = au.optJSONObject(j); if (a != null) names.add(a.optString("name")); }
                JSONObject ids = p.optJSONObject("externalIds"); String doi = ids == null ? "" : ids.optString("DOI");
                out.add(new LabBook.Source("", p.optString("title"), authors(names), p.isNull("year") ? "" : String.valueOf(p.optInt("year")), p.optString("venue"),
                    doi.isEmpty() ? p.optString("url") : "https://doi.org/" + doi, p.isNull("abstract") ? "" : p.optString("abstract"), "semantic"));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** The found papers without the same paper twice (by title), those without a title or abstract last, at most max. */
    public static List<LabBook.Source> merge(List<LabBook.Source> found, int max) {
        List<LabBook.Source> withAbstract = new ArrayList<>(), bare = new ArrayList<>(); java.util.Set<String> seen = new java.util.HashSet<>();
        for (LabBook.Source s : found) {
            String key = LabBook.normal(s.title()); if (key.isEmpty() || !seen.add(key)) continue;
            (s.summary().isBlank() ? bare : withAbstract).add(s);
        }
        List<LabBook.Source> out = new ArrayList<>(withAbstract); out.addAll(bare);
        return out.size() > max ? out.subList(0, max) : out;
    }

    // ── small readers ────────────────────────────────────────────────

    private static String tag(String xml, String name) { return first(xml, "<" + name + "[^>]*>(.*?)</" + name + ">"); }
    private static String attr(String xml, String name, String attribute) { return first(xml, "<" + Pattern.quote(name) + "[^>]*\\b" + attribute + "=\"([^\"]*)\""); }
    private static String first(String s, String regex) { Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(s); return m.find() ? m.group(1) : ""; }
    private static List<String> all(String s, String regex) { List<String> out = new ArrayList<>(); Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(s); while (m.find()) out.add(text(m.group(1))); return out; }
    private static String authors(List<String> names) {
        List<String> kept = new ArrayList<>(); for (String n : names) if (n != null && !n.isBlank()) kept.add(n.strip());
        if (kept.isEmpty()) return "";
        return kept.size() <= 3 ? String.join(", ", kept) : String.join(", ", kept.subList(0, 3)) + " 외";
    }
    /** Markup and entities out, spaces folded. */
    static String text(String s) {
        if (s == null) return "";
        String t = s.replaceAll("<!\\[CDATA\\[(.*?)]]>", "$1").replaceAll("<[^>]+>", " ")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
        return t.replaceAll("\\s+", " ").strip();
    }
}
