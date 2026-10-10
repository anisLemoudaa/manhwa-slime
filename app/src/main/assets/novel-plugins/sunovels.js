const cheerio = require("cheerio");
const { fetchApi } = require("@libs/fetch");

const BASE = "https://sunovels.com";

function absolute(url) {
  const value = String(url || "").trim();
  if (!value) return "";
  if (/^https?:\/\//i.test(value)) return value;
  if (value.startsWith("//")) return "https:" + value;
  return BASE + (value.startsWith("/") ? value : "/" + value);
}

function pathSegments(url) {
  return String(url || "")
    .replace(/^https?:\/\/[^/]+/i, "")
    .split(/[?#]/)[0]
    .split("/")
    .filter(Boolean);
}

function digits(value) {
  return String(value || "")
    .replace(/[٠-٩]/g, (d) => String("٠١٢٣٤٥٦٧٨٩".indexOf(d)))
    .replace(/[۰-۹]/g, (d) => String("۰۱۲۳۴۵۶۷۸۹".indexOf(d)));
}

function chapterNumber(value) {
  const normalized = digits(value);
  const match =
    normalized.match(/(?:الفصل|فصل|chapter)\s*[:：#-]?\s*(\d+(?:\.\d+)?)/i) ||
    normalized.match(/\d+(?:\.\d+)?/);
  return match ? Number(match[1] || match[0]) : undefined;
}

function novelUrl(path) {
  return absolute(path).split(/[?#]/)[0].replace(/\/+$/, "");
}

async function loadPage(url) {
  const response = await fetchApi(url);
  if (!response.ok) {
    const note = response.headers.get("x-msl-access-note");
    throw new Error(
      note ? "شمس الروايات منع طلب المصدر (تحقق/حظر وصول)." : `تعذر تحميل شمس الروايات: HTTP ${response.status}`,
    );
  }
  return cheerio.load(await response.text());
}

function coverFrom($, anchor) {
  let image = $(anchor).find("img").first();
  if (!image.length) image = $(anchor).closest("li, .novelBox, article").find("img").first();
  return absolute(
    image.attr("data-src") ||
      image.attr("data-lazy-src") ||
      image.attr("data-original") ||
      image.attr("src"),
  ) || null;
}

function novelsFrom($, selector) {
  const items = [];
  const seen = new Set();
  $(selector).each((_, element) => {
    const anchor = $(element).is("a") ? $(element) : $(element).find("a[href]").first();
    if (!anchor.length) return;
    const href = absolute(anchor.attr("href"));
    const parts = pathSegments(href);
    if (!href.startsWith(BASE) || parts.length !== 2 || parts[0] !== "novel") return;
    if (seen.has(href)) return;
    const name =
      anchor.find("h2, h3, h4, .title, .novel-title").first().text().trim() ||
      anchor.attr("title") ||
      anchor.text().trim();
    if (!name) return;
    seen.add(href);
    items.push({ name, path: href, cover: coverFrom($, anchor) });
  });
  return items;
}

const plugin = {
  id: "sunovels",
  name: "شمس الروايات",
  site: BASE,
  lang: "ar",
  version: "1.0.0",

  async popularNovels(_page, options) {
    const url = options && options.showLatestNovels
      ? `${BASE}/library`
      : `${BASE}/`;
    const $ = await loadPage(url);
    return novelsFrom($, 'a[href*="/novel/"]');
  },

  async searchNovels(query) {
    const $ = await loadPage(
      `${BASE}/search?title=${encodeURIComponent(String(query || "").trim())}`,
    );
    return novelsFrom($, 'a[href*="/novel/"]');
  },

  async parseNovel(path) {
    const url = novelUrl(path);
    const $ = await loadPage(`${url}?activeTab=chapters`);
    const header = $(".novel-header").first();
    if (!header.length) throw new Error("لم أجد صفحة الرواية في شمس الروايات.");
    const name =
      $('meta[property="og:title"]').attr("content") ||
      $("title").first().text().split("|")[0].trim() ||
      header.find("h1").first().text().trim();
    const image = header.find(".cover img, img").first();
    const chapters = [];
    const seen = new Set();
    $(".chaptersList .list-item").each((_, element) => {
      const row = $(element);
      const chapterName = row.find(".chapter-title").first().text().trim() || row.text().trim();
      const number = chapterNumber(chapterName);
      if (number === undefined) return;
      const chapterPath = `${url}/${number}`;
      if (seen.has(chapterPath)) return;
      seen.add(chapterPath);
      chapters.push({ name: chapterName, path: chapterPath, chapterNumber: number });
    });
    const genres = header
      .find(".categories a, .categories .tag")
      .toArray()
      .map((element) => $(element).text().trim())
      .filter(Boolean)
      .join(", ");
    const headerText = header.text().trim();
    const status = /مكتمل|مكتملة|Completed/i.test(headerText)
      ? "مكتمل"
      : /مستمر|Ongoing/i.test(headerText)
        ? "مستمر"
        : null;
    return {
      path: url,
      name,
      cover: absolute(image.attr("data-src") || image.attr("data-lazy-src") || image.attr("src")) || null,
      summary:
        $(".description, .synopsis, .novel-description").first().text().trim() ||
        $('meta[name="description"]').attr("content") ||
        $('meta[property="og:description"]').attr("content") ||
        null,
      author: header.find(".author").first().text().trim() || null,
      status,
      genres: genres || null,
      chapters,
      totalPages: 1,
    };
  },

  async parseChapter(path) {
    const $ = await loadPage(absolute(path));
    const content = $(".chapter-content").first();
    const chapterText = content.text().trim();
    if (!content.length || !content.html() || !chapterText) {
      throw new Error("تعذر العثور على نص الفصل في شمس الروايات.");
    }
    if (/الفصل المطلوب مقفل|جميع الفصول مفتوحة في التطبيق/.test(chapterText)) {
      throw new Error("هذا الفصل مقفل خارج تطبيق الموقع، ولا توجد له قراءة نصية عامة. لن نتجاوز قفل الموقع.");
    }
    // Keep the site's chapter HTML intact, including its embedded watermark paragraphs.
    return content.html();
  },
};

module.exports = plugin;
