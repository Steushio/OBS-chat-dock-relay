const { ipcRenderer } = require('electron');

console.log('[OBS Chat Interceptor] Chat injection script loaded. Initializing observer...');



// Helper to extract message content, transforming emoji images into their text alt codes
function parseMessageContent(messageEl) {
  if (!messageEl) return '';
  let text = '';
  messageEl.childNodes.forEach(node => {
    if (node.nodeType === Node.TEXT_NODE) {
      text += node.textContent;
    } else if (node.nodeName === 'IMG') {
      const alt = node.getAttribute('alt');
      if (alt) {
        text += alt;
      } else {
        // Fallback to source or empty
        text += '';
      }
    } else {
      text += node.textContent || '';
    }
  });
  return text.trim();
}

// Helper to extract badges
function parseBadges(node) {
  const badges = [];
  const badgeContainers = node.querySelectorAll('yt-live-chat-author-badge-renderer');
  
  badgeContainers.forEach(bc => {
    const tooltip = bc.getAttribute('tooltip') || '';
    const tooltipLower = tooltip.toLowerCase();
    
    if (tooltipLower.includes('moderator')) {
      badges.push('moderator');
    } else if (tooltipLower.includes('owner') || tooltipLower.includes('broadcaster')) {
      badges.push('owner');
    } else if (tooltipLower.includes('member')) {
      badges.push('member');
    } else if (tooltip.length > 0) {
      badges.push(tooltip.toLowerCase());
    }
  });

  return badges;
}

// Parse single chat element
function parseChatElement(node) {
  try {
    const id = node.getAttribute('id') || `msg_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
    const tagName = node.tagName.toLowerCase();
    const timestamp = Date.now();

    // 1. STANDARD TEXT MESSAGES
    if (tagName === 'yt-live-chat-text-message-renderer') {
      const authorEl = node.querySelector('#author-name');
      const messageEl = node.querySelector('#message');
      const avatarEl = node.querySelector('#avatar img');

      const username = authorEl ? authorEl.textContent.trim() : 'Anonymous';
      const message = messageEl ? parseMessageContent(messageEl) : '';
      const avatar = avatarEl ? avatarEl.getAttribute('src') : '';
      const badges = parseBadges(node);

      return {
        type: 'chat',
        id,
        username,
        message,
        avatar,
        badges,
        timestamp
      };
    }

    // 2. SUPER CHATS / DONATIONS
    if (tagName === 'yt-live-chat-paid-message-renderer') {
      const authorEl = node.querySelector('#author-name');
      const messageEl = node.querySelector('#message');
      const amountEl = node.querySelector('#purchase-amount');
      const avatarEl = node.querySelector('#avatar img');
      
      const username = authorEl ? authorEl.textContent.trim() : 'Supporter';
      const message = messageEl ? parseMessageContent(messageEl) : '';
      const amount = amountEl ? amountEl.textContent.trim() : '$0';
      const avatar = avatarEl ? avatarEl.getAttribute('src') : '';
      const badges = parseBadges(node);
      
      // Get background color of the header card to determine alert level color
      const headerEl = node.querySelector('#header');
      const backgroundColor = headerEl ? getComputedStyle(headerEl).backgroundColor : 'rgba(208, 2, 27, 1)';

      return {
        type: 'superchat',
        id,
        username,
        message,
        avatar,
        badges,
        amount,
        color: backgroundColor,
        timestamp
      };
    }

    // 3. SUPER STICKERS
    if (tagName === 'yt-live-chat-paid-sticker-renderer') {
      const authorEl = node.querySelector('#author-name');
      const amountEl = node.querySelector('#purchase-amount');
      const avatarEl = node.querySelector('#avatar img');
      const stickerImg = node.querySelector('#sticker img');
      
      const username = authorEl ? authorEl.textContent.trim() : 'Supporter';
      const amount = amountEl ? amountEl.textContent.trim() : '$0';
      const avatar = avatarEl ? avatarEl.getAttribute('src') : '';
      const stickerUrl = stickerImg ? stickerImg.getAttribute('src') : '';
      
      const cardEl = node.querySelector('#card');
      const backgroundColor = cardEl ? getComputedStyle(cardEl).backgroundColor : 'rgba(208, 2, 27, 1)';

      return {
        type: 'sticker',
        id,
        username,
        avatar,
        amount,
        stickerUrl,
        color: backgroundColor,
        message: `Sent a Super Sticker!`,
        timestamp
      };
    }

    // 4. MEMBERSHIPS (NEW / RENEWALS)
    if (tagName === 'yt-live-chat-membership-item-renderer') {
      const authorEl = node.querySelector('#author-name');
      const headerTextEl = node.querySelector('#header-primary-text');
      const subtextEl = node.querySelector('#header-subtext');
      const messageEl = node.querySelector('#message');
      const avatarEl = node.querySelector('#avatar img');

      const username = authorEl ? authorEl.textContent.trim() : 'Member';
      const heading = headerTextEl ? headerTextEl.textContent.trim() : '';
      const subtext = subtextEl ? subtextEl.textContent.trim() : '';
      const message = messageEl ? parseMessageContent(messageEl) : '';
      const avatar = avatarEl ? avatarEl.getAttribute('src') : '';

      return {
        type: 'membership',
        id,
        username,
        avatar,
        message: message || subtext || heading || 'Joined membership!',
        details: { heading, subtext },
        timestamp
      };
    }

    // 5. GIFTED MEMBERSHIPS (Sponsorship announcements)
    if (tagName === 'yt-live-chat-sponsorships-gift-purchase-announcement-renderer') {
      const authorEl = node.querySelector('#author-name');
      const detailsEl = node.querySelector('#header-primary-text');
      const avatarEl = node.querySelector('#avatar img');

      const username = authorEl ? authorEl.textContent.trim() : 'Gifter';
      const message = detailsEl ? detailsEl.textContent.trim() : 'Gifted memberships!';
      const avatar = avatarEl ? avatarEl.getAttribute('src') : '';

      return {
        type: 'membership_gift',
        id,
        username,
        avatar,
        message,
        timestamp
      };
    }

    // 6. REDEEMED MEMBERSHIP (Someone receives a gift)
    if (tagName === 'yt-live-chat-sponsorships-gift-redemption-announcement-renderer') {
      const authorEl = node.querySelector('#author-name');
      const detailsEl = node.querySelector('#message');
      const avatarEl = node.querySelector('#avatar img');

      const username = authorEl ? authorEl.textContent.trim() : 'Recipient';
      const message = detailsEl ? detailsEl.textContent.trim() : 'Was gifted a membership!';
      const avatar = avatarEl ? avatarEl.getAttribute('src') : '';

      return {
        type: 'membership_redeemed',
        id,
        username,
        avatar,
        message,
        timestamp
      };
    }

  } catch (err) {
    console.error('[OBS Chat Interceptor] Parsing error:', err);
  }
  return null;
}

// Live YouTube Poll Tracker
let lastPollHash = '';

function checkAndSendPoll() {
  try {
    const pollEl = document.querySelector('yt-live-chat-poll-renderer, yt-live-chat-viewer-engagement-message-renderer[has-poll], #banner yt-live-chat-poll-renderer, yt-live-chat-banner-header-renderer');
    if (!pollEl) {
      if (lastPollHash !== '') {
        lastPollHash = '';
        ipcRenderer.send('chat-event', { type: 'poll_ended', platform: 'youtube', timestamp: Date.now() });
      }
      return;
    }

    const questionEl = pollEl.querySelector('#question, #header, #title, #primary-text, yt-formatted-string#question');
    const totalVotesEl = pollEl.querySelector('#sub-header, #total-votes, #vote-count, #header-subtext, #secondary-text');
    const choiceEls = pollEl.querySelectorAll('yt-live-chat-poll-choice-renderer, #options > *, #choices > *, .poll-choice');

    const question = questionEl ? questionEl.textContent.trim() : 'YouTube Live Stream Poll';
    const totalVotes = totalVotesEl ? totalVotesEl.textContent.trim() : '';

    const choices = [];
    choiceEls.forEach((el, index) => {
      const textEl = el.querySelector('#text, #option-text, #text-content, yt-formatted-string');
      const ratioEl = el.querySelector('#percentage, #vote-percentage, .percentage, #ratio-bar');
      
      let text = textEl ? textEl.textContent.trim() : `Option ${index + 1}`;
      let percentage = ratioEl ? ratioEl.textContent.trim() : '0%';

      if (!percentage || percentage === '0%') {
        const styleWidth = el.querySelector('#ratio-bar')?.style?.width;
        if (styleWidth) percentage = styleWidth;
      }

      const isSelected = el.hasAttribute('is-selected') || el.classList.contains('selected');
      const isWinner = el.hasAttribute('is-winner') || el.classList.contains('winner');

      choices.push({
        text,
        percentage,
        isSelected,
        isWinner
      });
    });

    if (choices.length === 0) return;

    const pollData = {
      type: 'poll',
      platform: 'youtube',
      id: pollEl.getAttribute('id') || 'active_yt_poll',
      question,
      totalVotes,
      choices,
      timestamp: Date.now()
    };

    const currentHash = JSON.stringify({ question, totalVotes, choices });
    if (currentHash !== lastPollHash) {
      lastPollHash = currentHash;
      ipcRenderer.send('chat-event', pollData);
    }
  } catch (err) {
    console.error('[OBS Chat Interceptor] Error parsing poll:', err);
  }
}

// Function to initialize MutationObserver once elements are loaded
function initObserver() {
  const itemsContainer = document.querySelector('#items.yt-live-chat-item-list-renderer');
  
  if (!itemsContainer) {
    // Retry polling if chat is still initializing
    setTimeout(initObserver, 1000);
    return;
  }

  console.log('[OBS Chat Interceptor] Found chat items container. Setting up MutationObserver...');

  const observer = new MutationObserver((mutations) => {
    mutations.forEach(mutation => {
      mutation.addedNodes.forEach(node => {
        if (node.nodeType === Node.ELEMENT_NODE) {
          const parsed = parseChatElement(node);
          if (parsed) {
            ipcRenderer.send('chat-event', parsed);
          }
        }
      });
    });
  });

  observer.observe(itemsContainer, { childList: true });
  ipcRenderer.send('chat-observer-ready', { status: 'observing' });

  // Periodically check for active YouTube live polls every 1.5 seconds
  setInterval(checkAndSendPoll, 1500);
}

// Start polling for chat container
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initObserver);
} else {
  initObserver();
}
