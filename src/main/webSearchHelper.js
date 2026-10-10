/**
 * Web Search Helper for Live Stream AI Bot
 * Provides live internet knowledge via DuckDuckGo without any API keys.
 */

class WebSearchHelper {
  // Check if the query is directly answered by the streamer's description & knowledge base
  isCoveredByStreamContext(message) {
    if (!message) return false;
    const lower = message.toLowerCase().trim();
    const coveredPatterns = [
      /\b(mouse|mice|vxe|r1se)\b/i,
      /\b(keyboard|keys|fizz|redragon)\b/i,
      /\b(specs|pc specs|hardware|cpu|gpu|ram|processor|graphics card)\b/i,
      /\b(dpi|sens|sensitivity|edpi)\b/i,
      /\b(surgery|hand|health|left hand|one hand|one-hand|playstyle)\b/i,
      /\b(rank|ascendant|challenge|mmo mouse|yoru)\b/i,
      /\b(discord|tip|coffee|donate|donation)\b/i,
      /\b(who are you|streamer name|channel|rules|steushio|cutie bot)\b/i
    ];
    return coveredPatterns.some(pattern => pattern.test(lower));
  }

  // Check if a viewer message is asking a question suitable for internet search
  isSearchableQuery(message) {
    if (!message || message.length < 4) return false;
    const lower = message.toLowerCase().trim();

    // Check for question or search patterns
    const searchPatterns = [
      /[?]$/,
      /^(who|what|when|where|why|how|which|whose)\b/i,
      /^(is|are|was|were|did|does|do|will|can|could|should|would)\b/i,
      /\b(release date|news|score|weather|price|definition|meaning of|worth|cost)\b/i,
      /^(search|google)\s+for\b/i
    ];

    return searchPatterns.some(pattern => pattern.test(lower));
  }

  // Clean HTML tags and entities
  cleanSnippet(html) {
    if (!html) return '';
    return html
      .replace(/<[^>]+>/g, '')
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/\s+/g, ' ')
      .trim();
  }

  async search(query) {
    if (!query) return null;
    const cleanQuery = query
      .replace(/^[!?]/, '')
      .replace(/^(!ai|ai|search|google)\s+/i, '')
      .trim();

    if (!cleanQuery) return null;

    // 1. Try DuckDuckGo Instant Answer API (JSON)
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 2500);

      const apiUrl = `https://api.duckduckgo.com/?q=${encodeURIComponent(cleanQuery)}&format=json&no_html=1&skip_disambig=1`;
      const res = await fetch(apiUrl, { signal: controller.signal });
      clearTimeout(timeout);

      if (res.ok) {
        const json = await res.json();
        if (json.Answer) {
          return this.cleanSnippet(json.Answer);
        }
        if (json.AbstractText && json.AbstractText.length > 20) {
          return this.cleanSnippet(json.AbstractText);
        }
        if (Array.isArray(json.RelatedTopics) && json.RelatedTopics.length > 0) {
          const firstTopic = json.RelatedTopics[0];
          if (firstTopic.Text) {
            return this.cleanSnippet(firstTopic.Text);
          }
        }
      }
    } catch (e) {
      // Timeout or network error, proceed to HTML fallback
    }

    // 2. Fallback: DuckDuckGo HTML Search
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 2500);

      const htmlUrl = `https://html.duckduckgo.com/html/?q=${encodeURIComponent(cleanQuery)}`;
      const res = await fetch(htmlUrl, {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
        },
        signal: controller.signal
      });
      clearTimeout(timeout);

      if (res.ok) {
        const html = await res.text();
        const snippetMatches = html.match(/<a class="result__snippet[^>]*>(.*?)<\/a>/g);
        if (snippetMatches && snippetMatches.length > 0) {
          const topSnippets = snippetMatches.slice(0, 2).map(s => this.cleanSnippet(s)).filter(Boolean);
          if (topSnippets.length > 0) {
            return topSnippets.join(' ');
          }
        }
      }
    } catch (e) {
      // Safe fallback
    }

    // 3. Fallback: Wikipedia OpenSearch API (Super fast, reliable & free)
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 2000);
      const wikiUrl = `https://en.wikipedia.org/w/api.php?action=opensearch&search=${encodeURIComponent(cleanQuery)}&limit=1&namespace=0&format=json`;
      const res = await fetch(wikiUrl, { signal: controller.signal });
      clearTimeout(timeout);
      if (res.ok) {
        const json = await res.json();
        // Format: [query, [titles], [descriptions], [urls]]
        if (Array.isArray(json) && Array.isArray(json[2]) && json[2].length > 0 && json[2][0].length > 15) {
          return this.cleanSnippet(json[2][0]);
        }
      }
    } catch (e) {
      // Safe fallback
    }

    return null;
  }
}

module.exports = new WebSearchHelper();
