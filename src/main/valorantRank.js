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
    const puuid = (this.configStore?.get('valorantPuuid') || '').trim();
    const region = (this.configStore?.get('valorantRegion') || 'ap').trim().toLowerCase();
    const apiKey = (this.configStore?.get('henrikApiKey') || '').trim();

    let result = null;

    // 1. If permanent PUUID is provided, query PUUID endpoints first
    if (puuid) {
      const v3PuuidUrl = `https://api.henrikdev.xyz/valorant/v3/by-puuid/mmr/${region}/pc/${puuid}`;
      const v2PuuidUrl = `https://api.henrikdev.xyz/valorant/v2/by-puuid/mmr/${region}/${puuid}`;
      result = await this.queryHenrik(v3PuuidUrl, apiKey, name, tag, region, puuid);
      if (!result.success && !result.message.includes('Unauthorized')) {
        result = await this.queryHenrik(v2PuuidUrl, apiKey, name, tag, region, puuid);
      }
    }

    // 2. Fallback to Name & Tag
    if (!result || (!result.success && !result.message.includes('Unauthorized'))) {
      const v3Url = `https://api.henrikdev.xyz/valorant/v3/mmr/${region}/pc/${encodeURIComponent(name)}/${encodeURIComponent(tag)}`;
      const v2Url = `https://api.henrikdev.xyz/valorant/v2/mmr/${region}/${encodeURIComponent(name)}/${encodeURIComponent(tag)}`;
      const v1Url = `https://api.henrikdev.xyz/valorant/v1/mmr/${region}/${encodeURIComponent(name)}/${encodeURIComponent(tag)}`;

      let nameResult = await this.queryHenrik(v3Url, apiKey, name, tag, region, puuid);
      if (!nameResult.success && !nameResult.message.includes('Unauthorized')) {
        nameResult = await this.queryHenrik(v2Url, apiKey, name, tag, region, puuid);
      }
      if (!nameResult.success && !nameResult.message.includes('Unauthorized')) {
        nameResult = await this.queryHenrik(v1Url, apiKey, name, tag, region, puuid);
      }
      result = nameResult;
    }

    if (result.success) {
      if (result.puuid && !this.configStore?.get('valorantPuuid')) {
        this.configStore?.set('valorantPuuid', result.puuid);
      }
      this.cachedResult = result;
      this.lastFetchTime = Date.now();
    }
    return result;
  }

  queryHenrik(url, apiKey, name, tag, region, puuid) {
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
                name, tag, puuid,
                rank: 'Ascendant Goal',
                message: `🎮 ${name}#${tag}: Challenging for Ascendant (one hand MMO mouse)! [Henrik API Key required in app settings for live RR]`
              });
            }
            if (res.statusCode === 404) {
              const targetDesc = puuid ? `PUUID ${puuid}` : `${name}#${tag}`;
              return resolve({
                success: false,
                name, tag, puuid,
                rank: 'Unknown',
                message: `🎮 Player ${targetDesc} not found on region '${region}'. Please check details in Bot Settings!`
              });
            }
            if (res.statusCode === 200 && data) {
              const json = JSON.parse(data);
              const dataObj = json.data;
              if (dataObj) {
                let parsedPuuid = puuid;
                let parsedName = name;
                let parsedTag = tag;
                let rankPatched = 'Unrated';
                let rr = 0;
                let elo = 0;
                let change = 0;
                let peakRank = '';

                // v3 schema
                if (dataObj.current) {
                  rankPatched = dataObj.current.tier?.name || 'Unrated';
                  rr = dataObj.current.rr || 0;
                  elo = dataObj.current.elo || 0;
                  change = dataObj.current.last_change || 0;
                  if (dataObj.account) {
                    parsedPuuid = dataObj.account.puuid || parsedPuuid;
                    parsedName = dataObj.account.name || parsedName;
                    parsedTag = dataObj.account.tag || parsedTag;
                  }
                  if (dataObj.peak && dataObj.peak.tier) {
                    peakRank = dataObj.peak.tier.name || '';
                  }
                }
                // v2 schema
                else if (dataObj.current_data) {
                  const cur = dataObj.current_data;
                  rankPatched = cur.currenttierpatched || 'Unrated';
                  rr = cur.ranking_in_tier || 0;
                  elo = cur.elo || 0;
                  change = cur.mmr_change_to_last_game || 0;
                  parsedPuuid = dataObj.puuid || parsedPuuid;
                  parsedName = dataObj.name || parsedName;
                  parsedTag = dataObj.tag || parsedTag;
                  if (dataObj.highest_rank) {
                    peakRank = dataObj.highest_rank.patched_tier || '';
                  }
                }
                // v1 schema
                else if (dataObj.currenttierpatched) {
                  rankPatched = dataObj.currenttierpatched || 'Unrated';
                  rr = dataObj.ranking_in_tier || 0;
                  elo = dataObj.elo || 0;
                  change = dataObj.mmr_change_to_last_game || 0;
                  parsedPuuid = dataObj.puuid || parsedPuuid;
                  parsedName = dataObj.name || parsedName;
                  parsedTag = dataObj.tag || parsedTag;
                }

                const changeStr = change >= 0 ? `+${change}` : `${change}`;
                const peakStr = (peakRank && peakRank !== 'null' && peakRank !== rankPatched) ? ` (Peak: ${peakRank})` : '';
                return resolve({
                  success: true,
                  name: parsedName,
                  tag: parsedTag,
                  puuid: parsedPuuid,
                  rank: rankPatched,
                  rr, elo, change, peakRank,
                  message: `🎮 ${parsedName}#${parsedTag}: ${rankPatched} (${rr} RR | ELO ${elo}) [${changeStr} last match]${peakStr}`
                });
              }
            }
            resolve({
              success: false,
              name, tag, puuid,
              rank: 'Ascendant Challenge',
              message: `🎮 ${name} is doing a one-handed MMO mouse challenge to reach Ascendant in Valorant!`
            });
          } catch (e) {
            resolve({
              success: false,
              name, tag, puuid,
              rank: 'Ascendant Challenge',
              message: `🎮 ${name} is challenging for Ascendant in Valorant (1-hand MMO mouse only)!`
            });
          }
        });
      });
      req.on('error', () => {
        resolve({
          success: false,
          name, tag, puuid,
          rank: 'Ascendant Challenge',
          message: `🎮 ${name} is challenging for Ascendant in Valorant (1-hand MMO mouse only)!`
        });
      });
    });
  }
}

module.exports = ValorantRankService;
