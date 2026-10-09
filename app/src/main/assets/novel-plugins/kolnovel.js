const cheerio = require("cheerio");
const { fetchApi } = require("@libs/fetch");

const BASE = "https://kolnovel.com";

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
      note ? "ملوك الروايات منع طلب المصدر (تحقق/حظر وصول)." : `تعذر تحميل ملوك الروايات: HTTP ${response.status}`,
    );
  }
  return cheerio.load(await response.text());
}

function coverFrom($, anchor) {
  let image = $(anchor).find("img").first();
  if (!image.length) {
    image = $(anchor).closest(".bs, .bsx, article, li, .sertoimg, .sertothumb").find("img").first();
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
    if (!href.startsWith(BASE) || parts.length !== 2 || parts[0] !== "series") return;
    if (seen.has(href)) return;
    const name =
      anchor.find("h2, h3, h4, .tt, .seriestu, .item-title, .title").first().text().trim() ||
      anchor.attr("title") ||
      anchor.text().trim();
    if (!name) return;
    seen.add(href);
    items.push({ name, path: href, cover: coverFrom($, anchor) });
  });
  return items;
}

function fieldValue($, label) {
  let result = "";
  $(".sertoinfo .sername").each((_, element) => {
    if (result || !$(element).text().includes(label)) return;
    result = $(element).parent().find(".serval").first().text().trim();
  });
  return result;
}

const plugin = {
  id: "kolnovel",
  name: "ملوك الروايات",
  site: BASE,
  lang: "ar",
  version: "1.0.0",

  async popularNovels(page, options) {
    const pageNumber = Math.max(1, Number(page) || 1);
    if (options && options.showLatestNovels) {
      const pagePart = pageNumber > 1 ? `page/${pageNumber}/` : "";
      const $ = await loadPage(`${BASE}/series/${pagePart}?status=&type=&order=update`);
      return novelsFrom($, 'a[href*="/series/"]');
    }
    const $ = await loadPage(`${BASE}/`);
    return novelsFrom($, ".bsx a[href], .bs a[href]");
  },

  async searchNovels(query, page) {
    const pageNumber = Math.max(1, Number(page) || 1);
    const pagePart = pageNumber > 1 ? `page/${pageNumber}/` : "";
    const $ = await loadPage(`${BASE}/${pagePart}?s=${encodeURIComponent(String(query || "").trim())}`);
    return novelsFrom($, '.listupd a[href*="/series/"]');
  },

  async parseNovel(path) {
    const url = absolute(path);
    const $ = await loadPage(url);
    const info = $(".sertoinfo").first();
    const name = info.find(".entry-title").first().text().trim();
    if (!info.length || !name) throw new Error("لم أجد صفحة الرواية في ملوك الروايات.");
    const image = $(".sertothumb img, .sertoimg img").first();
    const chapters = [];
    const seen = new Set();
    $(".bxcl a[href*='shaag24']").each((_, element) => {
      const anchor = $(element);
      const chapterPath = absolute(anchor.attr("href"));
      if (!chapterPath || seen.has(chapterPath)) return;
      const chapterName =
        anchor.find(".epl-title, .epl-num, .epl-ttl").first().text().trim() || anchor.text().trim();
      if (!chapterName) return;
      seen.add(chapterPath);
      chapters.push({ name: chapterName, path: chapterPath, chapterNumber: chapterNumber(chapterName) });
    });
    const genres = info
      .find('a[href*="/genre/"], a[href*="/genres/"]')
      .toArray()
      .map((element) => $(element).text().trim())
      .filter(Boolean)
      .filter((value, index, values) => values.indexOf(value) === index)
      .join(", ");
    return {
      path: url,
      name,
      cover: absolute(image.attr("data-src") || image.attr("data-lazy-src") || image.attr("src")) || null,
      summary: $(".sersys").first().text().trim() || info.find("p").first().text().trim() || null,
      author: fieldValue($, "الكاتب") || null,
      artist: fieldValue($, "المترجم") || null,
      status: $(".sertostat").first().text().trim() || $(".sertobig").text().trim().slice(0, 40) || null,
      genres: genres || null,
      chapters,
      totalPages: 1,
    };
  },

  async parseChapter(path) {
    const $ = await loadPage(absolute(path));
    const content = $(".epcontent.entry-content").first();
    if (!content.length) throw new Error("تعذر العثور على نص الفصل في ملوك الروايات.");
    content.find(".bottomnav, .jumpnav, .nav-links, script, style, iframe").remove();
    const html = content.html();
    if (!html || !content.text().trim()) throw new Error("الفصل لا يحتوي نصًا قابلًا للعرض.");
    return html;
  },
};

module.exports = plugin;
