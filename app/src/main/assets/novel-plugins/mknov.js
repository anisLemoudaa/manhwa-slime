const cheerio = require("cheerio");
const { fetchApi } = require("@libs/fetch");

const BASE = "https://mknov.com";

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
      note ? "مملكة الروايات منع طلب المصدر (تحقق/حظر وصول)." : `تعذر تحميل مملكة الروايات: HTTP ${response.status}`,
    );
  }
  return cheerio.load(await response.text());
}

function coverFrom($, anchor) {
  let image = $(anchor).find("img").first();
  if (!image.length) image = $(anchor).closest("article, li, div").find("img").first();
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
    if (!/^\d+$/.test(parts[1]) || seen.has(href)) return;
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

async function libraryWorks(sort, page) {
  const pageNumber = Math.max(1, Number(page) || 1);
  const url = `${BASE}/api/library?limit=30&page=${pageNumber}&sort=${encodeURIComponent(sort)}`;
  const response = await fetchApi(url);
  if (!response.ok) {
    const note = response.headers.get("x-msl-access-note");
    throw new Error(
      note ? "واجهة مكتبة مملكة الروايات رفضت الطلب (تحقق/حظر وصول)." : `تعذر تحميل مكتبة مملكة الروايات: HTTP ${response.status}`,
    );
  }
  const data = await response.json();
  return Array.isArray(data.works) ? data.works : [];
}

function itemFromWork(work) {
  const id = String(work.id || work.slug || "").trim();
  const name = String(work.titleAr || work.title || "").trim();
  if (!/^\d+$/.test(id) || !name) return null;
  return {
    name,
    path: `${BASE}/novel/${id}`,
    cover: absolute(work.image) || null,
  };
}

const plugin = {
  id: "mknov",
  name: "مملكة الروايات",
  site: BASE,
  lang: "ar",
  version: "1.0.0",

  async popularNovels(page, options) {
    const sort = options && options.showLatestNovels ? "newest" : "views";
    return (await libraryWorks(sort, page)).map(itemFromWork).filter(Boolean);
  },

  async searchNovels(query, page) {
    const needle = String(query || "").trim().toLocaleLowerCase();
    if (!needle) return [];
    const works = await libraryWorks("views", page);
    return works
      .filter((work) => [work.titleAr, work.title].some((title) => String(title || "").toLocaleLowerCase().includes(needle)))
      .map(itemFromWork)
      .filter(Boolean);
  },

  async parseNovel(path) {
    const url = absolute(path).split(/[?#]/)[0].replace(/\/+$/, "");
    const $ = await loadPage(url);
    let schema = {};
    try {
      schema = JSON.parse($('script[type="application/ld+json"]').first().text() || "{}");
    } catch (_) {
      schema = {};
    }
    const name = schema.name || $("main h1").first().text().trim();
    if (!name) throw new Error("لم أجد صفحة الرواية في مملكة الروايات.");
    const rawImage = Array.isArray(schema.image) ? schema.image[0] : schema.image;
    const image = rawImage || $("main img").first().attr("src");
    const chapters = [];
    const seen = new Set();
    $('a[href*="/chapter/"]').each((_, element) => {
      const anchor = $(element);
      const chapterPath = absolute(anchor.attr("href"));
      if (!chapterPath || seen.has(chapterPath)) return;
      const chapterName = anchor.text().trim();
      if (!chapterName) return;
      seen.add(chapterPath);
      chapters.push({ name: chapterName, path: chapterPath, chapterNumber: chapterNumber(chapterName) });
    });
    const author = Array.isArray(schema.author)
      ? schema.author.map((item) => item.name || item).join(", ")
      : schema.author && typeof schema.author === "object"
        ? schema.author.name
        : schema.author;
    return {
      path: url,
      name,
      cover: absolute(image) || null,
      summary: schema.description || null,
      author: author || null,
      genres: Array.isArray(schema.genre) ? schema.genre.join(", ") : schema.genre || null,
      status: null,
      chapters,
      totalPages: 1,
    };
  },

  async parseChapter() {
    throw new Error(
      "قراءة فصول MKNOV متوقفة: طلب HTTP المباشر يعيد نصًا مشوّشًا. لن نفك حماية المحتوى أو نتجاوزها. أتاح الموقع صفحة قراءة نصية عامة أو واجهة موثّقة لإكمال القراءة.",
    );
  },
};

module.exports = plugin;
