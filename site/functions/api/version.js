export async function onRequestGet({ env }) {
  const url = env.SUPABASE_URL;
  const key = env.SUPABASE_ANON_KEY;
  if (!url || !key) return new Response(JSON.stringify({ error: 'version service is not configured' }), { status: 503, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' } });
  const endpoint = `${url}/rest/v1/app_releases?select=version_name,version_code,title,release_notes,download_url,storage_path,file_size,mandatory,published_at&status=eq.published&limit=1`;
  const r = await fetch(endpoint, { headers: { apikey: key, Authorization: `Bearer ${key}` } });
  if (!r.ok) return new Response(JSON.stringify({ error: 'version lookup failed' }), { status: 502, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' } });
  const rows = await r.json();
  if (!rows.length) return new Response(JSON.stringify({ available: false, appName: 'MANHWA SLIME' }), { headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'public, max-age=60' } });
  const v = rows[0];
  const downloadUrl = v.download_url || (v.storage_path ? `${url}/storage/v1/object/public/app-releases/${v.storage_path}` : null);
  return new Response(JSON.stringify({ available: Boolean(downloadUrl), appName: 'MANHWA SLIME', versionName: v.version_name, versionCode: v.version_code, title: v.title, releaseNotes: v.release_notes, downloadUrl, mandatory: v.mandatory, fileSize: v.file_size, publishedAt: v.published_at }), { headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'public, max-age=60' } });
}
