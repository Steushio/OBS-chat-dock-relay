const https = require('https');

class ValorantRankService {
  constructor(configStore) {
    this.configStore = configStore;
    this.cachedResult = null;
    this.lastFetchTime = 0;
    this.rankPattern = /\b(what('s|\s+is)?\s+(your|ur|the)?\s*rank|current\s*rank|which\s*rank|valo(rant)?\s*rank|rank\s*kya\s*(hai|hey)|kya\s*rank\s*(hai|hey)|rank\s*batao|rank\s*plz|rank\s*please)\b/i;
  }

  isRankQuery(message) {
    const trimmed = (message || '').trim().toLowerCase();
    if (trimmed === '!rank' || trimmed.startsWith('!rank ') ||
        trimmed === '!val' || trimmed.startsWith('!val ') ||
        trimmed === '!valo' || trimmed.startsWith('!valo ') ||
        trimmed === '!valorant' || trimmed.startsWith('!valorant ') ||
        trimmed === '!mmr' || trimmed.startsWith('!mmr ') ||
        trimmed === '!tier' || trimmed.startsWith('!tier ') ||
        trimmed === 'rank' || trimmed === 'rank?' ||
        trimmed === 'valo rank' || trimmed === 'valo rank?' ||
        trimmed === 'valorant rank' || trimmed === 'valorant rank?') {
      return true;
    }
    return this.rankPattern.test(trimmed);
  }

  async getRankInfo(forceRefresh = false) {
    const now = Date.now();
    if (!forceRefresh && this.cachedResult && (now - this.lastFetchTime < 60000)) {
      return this.cachedResult;
    }

    const name = (this.configStore?.get('valorantName') || 'Steushio').trim();
    const tag = (this.configStore?.get('valorantTag') || 'IND').trim();
    const region = (this.configStore?.get('valorantRegion') || 'ap').trim().toLowerCase();
    const apiKey = (this.configStore?.get('henrikApiKey') || '').trim();

    const v2Url = `https://api.henrikdev.xyz/valorant/v2/mmr/${region}/${encodeURIComponent(name)}/${encodeURIComponent(tag)}`;
    const v1Url = `https://api.henrikdev.xyz/valorant/v1/mmr/${region}/${encodeURIComponent(name)}/${encodeURIComponent(tag)}`;

    let result = await this.queryHenrik(v2Url, apiKey, true, name, tag, region);
    if (!result.success && !result.message.includes('Unauthorized')) {
      result = await this.queryHenrik(v1Url, apiKey, false, name, tag, region);
    }

    if (result.success) {
      this.cachedResult = result;
      this.lastFetchTime = Date.now();
    }
    return result;
  }

  queryHenrik(url, apiKey, isV2, name, tag, region) {
    return new Promise((resolve) => {
      const headers = { 'User-Agent': 'OBS-Chat-Dock-Relay/1.0' };
      if (apiKey) headers['Authorization'] = apiKey;

      const req = https.get(url, { headers, timeout: 8000 }, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          try {
            if (res.statusCode === 401 || res.statusCode === 403) {
              return resolve({
                success: false,
                name, tag,
                rank: 'Ascendant Goal',
                message: `🎮 ${name}#${tag}: Challenging for Ascendant (one hand MMO mouse)! [Henrik API Key required in app settings for live RR]`
              });
            }
            if (res.statusCode === 404) {
              return resolve({
                success: false,
                name, tag,
                rank: 'Unknown',
                message: `🎮 Player ${name}#${tag} not found on region '${region}'. Please check Riot ID in Bot Settings!`
              });
            }
            if (res.statusCode === 200 && data) {
              const json = JSON.parse(data);
              const dataObj = json.data;
              if (isV2 && dataObj && dataObj.current_data) {
                const cur = dataObj.current_data;
                const rankPatched = cur.currenttierpatched || 'Unrated';
                const rr = cur.ranking_in_tier || 0;
                const elo = cur.elo || 0;
                const change = cur.mmr_change_to_last_game || 0;
                const changeStr = change >= 0 ? `+${change}` : `${change}`;
                return resolve({
                  success: true,
                  name, tag,
                  rank: rankPatched,
                  rr, elo,
                  change,
                  message: `🎮 ${name}#${tag}: ${rankPatched} (${rr} RR | ELO ${elo}) [${changeStr} last match]`
                });
              } else if (dataObj) {
                const rankPatched = dataObj.currenttierpatched || 'Unrated';
                const rr = dataObj.ranking_in_tier || 0;
                const elo = dataObj.elo || 0;
                const change = dataObj.mmr_change_to_last_game || 0;
                const changeStr = change >= 0 ? `+${change}` : `${change}`;
                return resolve({
                  success: true,
                  name, tag,
                  rank: rankPatched,
                  rr, elo,
                  change,
                  message: `🎮 ${name}#${tag}: ${rankPatched} (${rr} RR | ELO ${elo}) [${changeStr} last match]`
                });
              }
            }
            resolve({
              success: false,
              name, tag,
              rank: 'Ascendant Challenge',
              message: `🎮 ${name} is doing a one-handed MMO mouse challenge to reach Ascendant in Valorant!`
            });
          } catch (e) {
            resolve({
              success: false,
              name, tag,
              rank: 'Ascendant Challenge',
              message: `🎮 ${name} is challenging for Ascendant in Valorant (1-hand MMO mouse only)!`
            });
          }
        });
      });
      req.on('error', () => {
        resolve({
          success: false,
          name, tag,
          rank: 'Ascendant Challenge',
          message: `🎮 ${name} is challenging for Ascendant in Valorant (1-hand MMO mouse only)!`
        });
      });
    });
  }
}

module.exports = ValorantRankService;
