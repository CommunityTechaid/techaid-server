/**
 * Edge-served robots.txt for api.communitytechaid.org.uk
 *
 * Why this exists:
 *   api-production is an Azure Container App that scales to zero. Any request that
 *   reaches the origin wakes the container and incurs a cold start. Measured in
 *   Application Insights (AppRequests, 14d to 2026-07-29): /robots.txt hit the origin
 *   ~2x/day, every one a 404 via the UnknownPathFilter allow-list, and each one is a
 *   potential wake. Well-behaved crawlers (bingbot, Googlebot, OAI-SearchBot,
 *   Applebot) fetch robots.txt before anything else.
 *
 *   Serving robots.txt at the edge means those crawlers get an answer from Cloudflare
 *   and never touch the origin.
 *
 * Note this does NOT stop malicious scanners (.php probe paths etc.) - they ignore
 * robots.txt entirely. Blocking those is WAF/Transform-rule work at the zone level,
 * which is deliberately out of scope here.
 *
 * Before this Worker, the zone served a robots.txt consisting ONLY of Cloudflare's
 * Content Signals comment block, with no User-agent/Disallow directives at all -
 * i.e. it restricted nothing. The Content Signal declaration is preserved below.
 */

const ROBOTS_TXT = `# api.communitytechaid.org.uk is a JSON/GraphQL API, not a website.
# There is nothing here to index. Please do not crawl it.

User-agent: *
Content-Signal: search=no, ai-input=no, ai-train=no
Disallow: /

# Content signal meanings (per https://contentsignals.org):
#   search:   building a search index and providing search results.
#   ai-input: inputting content into one or more AI models (e.g. RAG, grounding).
#   ai-train: training or fine-tuning AI models.
#
# ANY RESTRICTIONS EXPRESSED VIA CONTENT SIGNALS ARE EXPRESS RESERVATIONS OF
# RIGHTS UNDER ARTICLE 4 OF THE EUROPEAN UNION DIRECTIVE 2019/790 ON COPYRIGHT
# AND RELATED RIGHTS IN THE DIGITAL SINGLE MARKET.
`;

export default {
  async fetch(request) {
    const url = new URL(request.url);

    // Safety net: the route should only ever send /robots.txt here. If the route is
    // ever widened by mistake, pass everything else straight through to the origin
    // rather than blackholing API traffic.
    if (url.pathname !== '/robots.txt') {
      return fetch(request);
    }

    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method Not Allowed', {
        status: 405,
        headers: { allow: 'GET, HEAD' },
      });
    }

    return new Response(request.method === 'HEAD' ? null : ROBOTS_TXT, {
      status: 200,
      headers: {
        'content-type': 'text/plain; charset=utf-8',
        // Let the edge and crawlers cache it; there is no reason to recompute.
        'cache-control': 'public, max-age=86400',
      },
    });
  },
};
