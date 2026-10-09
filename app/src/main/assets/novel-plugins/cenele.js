const cheerio = require("cheerio");
const { fetchApi } = require("@libs/fetch");

const BASE = "https://cenele.com";

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

async function loadPage(url) {
  const response = await fetchApi(url);
  if (!response.ok) {
    const note = response.headers.get("x-msl-access-note");
    throw new Error(
      note ? "فضاء الروايات منع طلب المصدر (تحقق/حظر وصول)." : `تعذر تحميل فضاء الروايات: HTTP ${response.status}`,
    );
  }
  return cheerio.load(await response.text());
}

function coverFrom($, anchor) {
  let image = $(anchor).find("img").first();
  if (!image.length) {
    image = $(anchor)
      .closest("article, li, .c-tabs-item__content, .page-item-detail, .item-summary")
      .find("img")
      .first();
  }
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
    if (!href.startsWith(BASE) || parts.length !== 2 || parts[0] !== "cont") return;
    if (seen.has(href)) return;
    const name =
      anchor.find("h2, h3, h4, .post-title, .item-title, .title").first().text().trim() ||
      anchor.attr("title") ||
      anchor.text().trim();
    if (!name) return;
    if (/مانهوا|مانجا|مانها|manhwa|manhua|manga/i.test(name) || /manhwa|manhua|manga/i.test(parts[1])) return;
    seen.add(href);
    items.push({ name, path: href, cover: coverFrom($, anchor) });
  });
  return items;
}

const plugin = {
  id: "cenele",
  name: "فضاء الروايات",
  site: BASE,
  lang: "ar",
  version: "1.0.0",

  async popularNovels(page, options) {
    const order = options && options.showLatestNovels ? "latest" : "views";
    const pagePart = Number(page) > 1 ? `page/${Number(page)}/` : "";
    const $ = await loadPage(`${BASE}/cont/${pagePart}?m_orderby=${order}`);
    return novelsFrom($, 'a[href*="/cont/"]');
  },

  async searchNovels(query, page) {
    const pagePart = Number(page) > 1 ? `page/${Number(page)}/` : "";
    const url = `${BASE}/${pagePart}?s=${encodeURIComponent(String(query || "").trim())}&post_type=wp-manga`;
    const $ = await loadPage(url);
    return novelsFrom($, 'a[href*="/cont/"]');
  },

  async parseNovel(path) {
    const url = absolute(path);
    const $ = await loadPage(url);
    const hero = $("article.nhv-novel-hero").first();
    const name = hero.find("h1.nhv-novel-title").first().text().trim();
    if (!hero.length || !name) throw new Error("لم أجد صفحة الرواية في فضاء الروايات.");
    const image = hero.find(".nhv-novel-cover img, img").first();
    const chapters = [];
    const seen = new Set();
    $(".wp-manga-chapter a[href]").each((_, element) => {
      const anchor = $(element);
      const chapterPath = absolute(anchor.attr("href"));
      if (!chapterPath || seen.has(chapterPath)) return;
      const chapterName = anchor.text().trim();
      if (!chapterName) return;
      seen.add(chapterPath);
      chapters.push({ name: chapterName, path: chapterPath, chapterNumber: chapterNumber(chapterName) });
    });
    const uniqueText = (selector) =>
      $(selector)
        .toArray()
        .map((element) => $(element).text().trim())
        .filter(Boolean)
        .filter((value, index, values) => values.indexOf(value) === index)
        .join(", ");
    return {
      path: url,
      name,
      cover: absolute(image.attr("data-src") || image.attr("data-lazy-src") || image.attr("src")) || null,
      summary: $(".nhv-novel-synopsis").first().text().trim() || null,
      status: hero.find(".nhv-novel-status").first().text().trim() || null,
      genres: uniqueText('a[href*="/genre/"], a[href*="/tag/"]') || null,
      author: uniqueText('a[href*="/author/"]') || null,
      chapters,
      totalPages: 1,
    };
  },

  async parseChapter(path) {
    const $ = await loadPage(absolute(path));
    const content = $(".reading-content story-layer").first();
    if (!content.length || !content.html()) {
      throw new Error("تعذر العثور على نص الفصل في فضاء الروايات.");
    }
    content.find(".nhv-reader-promo, input, script").remove();
    const hasParagraphText = content.find("p").toArray().some((element) => $(element).text().trim());
    if (!hasParagraphText) throw new Error("فصل فضاء الروايات لا يحتوي نصًا قابلًا للعرض.");
    return content.html();
  },
};

module.exports = plugin;
