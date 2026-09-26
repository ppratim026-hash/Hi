/**
 * Arushi AI Assistant - Multilingual Voice & Native Action Engine
 */

(function () {
  'use strict';

  // --- Platform Bridge Detection ---
  const nativeBridge = window.AndroidBridge || window.Android || null;
  const isAndroidNative = !!nativeBridge;

  // Retrieve Gemini API Key from Android Bridge or injected window variable
  let GEMINI_API_KEY = '';
  if (isAndroidNative && typeof nativeBridge.getApiKey === 'function') {
    try {
      GEMINI_API_KEY = nativeBridge.getApiKey() || '';
    } catch (e) {
      console.warn('Could not read API key from bridge', e);
    }
  }

  // --- UI Elements ---
  const statusDot = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');
  const bridgeBadge = document.getElementById('bridgeBadge');
  const bridgeText = document.getElementById('bridgeText');
  const permBanner = document.getElementById('permBanner');
  const permBtn = document.getElementById('permBtn');
  const permGrantBtn = document.getElementById('permGrantBtn');
  const orbContainer = document.getElementById('orbContainer');
  const waveCanvas = document.getElementById('waveCanvas');
  const stateLabel = document.getElementById('stateLabel');
  const languageTag = document.getElementById('languageTag');
  const langName = document.getElementById('langName');
  const actionCard = document.getElementById('actionCard');
  const actionIconWrap = document.getElementById('actionIconWrap');
  const actionTitle = document.getElementById('actionTitle');
  const actionSub = document.getElementById('actionSub');
  const chatFeed = document.getElementById('chatFeed');
  const inputForm = document.getElementById('inputForm');
  const textInput = document.getElementById('textInput');
  const micBtn = document.getElementById('micBtn');
  const interruptBtn = document.getElementById('interruptBtn');
  const chipsContainer = document.getElementById('chipsContainer');

  // --- State Variables ---
  let isListening = false;
  let isThinking = false;
  let isSpeaking = false;
  let audioContext = null;
  let analyser = null;
  let currentAudioSource = null;
  let speechRecognition = null;
  let conversationHistory = [];
  let detectedLanguage = 'English / Hindi';

  // --- Initialize Bridge UI ---
  if (isAndroidNative) {
    bridgeBadge.className = 'bridge-badge';
    bridgeText.textContent = 'Android Native';
    checkNativePermissions();
  } else {
    bridgeBadge.className = 'bridge-badge web-fallback';
    bridgeText.textContent = 'Web Fallback';
  }

  function checkNativePermissions() {
    if (!isAndroidNative || typeof nativeBridge.checkPermissions !== 'function') return;
    try {
      const perms = JSON.parse(nativeBridge.checkPermissions());
      if (!perms.contacts || !perms.call) {
        permBanner.style.display = 'flex';
      } else {
        permBanner.style.display = 'none';
      }
    } catch (e) {
      console.warn('Permission check error:', e);
    }
  }

  if (permGrantBtn) {
    permGrantBtn.addEventListener('click', () => {
      if (isAndroidNative && typeof nativeBridge.requestPermissions === 'function') {
        nativeBridge.requestPermissions();
        setTimeout(checkNativePermissions, 2000);
      }
    });
  }

  // --- Audio Context & Visualizer ---
  function getAudioContext() {
    if (!audioContext) {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      audioContext = new AudioCtx({ sampleRate: 24000 });
      analyser = audioContext.createAnalyser();
      analyser.fftSize = 64;
    }
    if (audioContext.state === 'suspended') {
      audioContext.resume();
    }
    return audioContext;
  }

  // Canvas visualizer animation
  const ctx = waveCanvas.getContext('2d');
  let animationId = null;

  function renderVisualizer() {
    const width = waveCanvas.width;
    const height = waveCanvas.height;
    ctx.clearRect(0, 0, width, height);

    if (isSpeaking && analyser) {
      const bufferLength = analyser.frequencyBinCount;
      const dataArray = new Uint8Array(bufferLength);
      analyser.getByteFrequencyData(dataArray);

      const centerX = width / 2;
      const centerY = height / 2;
      const baseRadius = 38;

      ctx.save();
      ctx.beginPath();
      for (let i = 0; i < bufferLength; i++) {
        const angle = (i / bufferLength) * Math.PI * 2;
        const amplitude = (dataArray[i] / 255) * 22;
        const r = baseRadius + amplitude;
        const x = centerX + Math.cos(angle) * r;
        const y = centerY + Math.sin(angle) * r;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.closePath();
      ctx.strokeStyle = 'rgba(236, 72, 153, 0.85)';
      ctx.lineWidth = 3;
      ctx.stroke();
      ctx.restore();
    } else if (isListening) {
      // Gentle pulsing ring
      const time = Date.now() / 400;
      const r = 38 + Math.sin(time) * 8;
      ctx.save();
      ctx.beginPath();
      ctx.arc(width / 2, height / 2, r, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(6, 182, 212, 0.8)';
      ctx.lineWidth = 2.5;
      ctx.stroke();
      ctx.restore();
    }

    animationId = requestAnimationFrame(renderVisualizer);
  }
  renderVisualizer();

  // --- Stop / Interruption Handler (Test Case 10) ---
  function interruptArushi() {
    if (currentAudioSource) {
      try {
        currentAudioSource.stop();
      } catch (e) {}
      currentAudioSource = null;
    }
    isSpeaking = false;
    interruptBtn.style.display = 'none';
    orbContainer.classList.remove('speaking');
    updateStatus('ready', 'Ready');
    setStateLabel('Listening stopped. Tap mic to speak.');
  }

  interruptBtn.addEventListener('click', interruptArushi);

  // --- UI State Management ---
  function updateStatus(state, text) {
    statusDot.className = 'status-dot ' + (state || '');
    statusText.textContent = text || 'Ready';

    if (state === 'speaking') {
      orbContainer.classList.add('speaking');
      orbContainer.classList.remove('listening');
      interruptBtn.style.display = 'flex';
    } else if (state === 'listening') {
      orbContainer.classList.add('listening');
      orbContainer.classList.remove('speaking');
      interruptBtn.style.display = 'none';
    } else {
      orbContainer.classList.remove('speaking', 'listening');
      interruptBtn.style.display = 'none';
    }
  }

  function setStateLabel(text) {
    stateLabel.textContent = text;
  }

  function showActionCard(icon, title, subtitle) {
    actionIconWrap.textContent = icon;
    actionTitle.textContent = title;
    actionSub.textContent = subtitle;
    actionCard.style.display = 'flex';

    setTimeout(() => {
      actionCard.style.display = 'none';
    }, 4500);
  }

  function appendMessage(role, text, actionInfo) {
    const bubble = document.createElement('div');
    bubble.className = `message-bubble ${role}`;

    const sender = document.createElement('div');
    sender.className = 'sender-name';
    sender.textContent = role === 'user' ? 'You' : 'Arushi';
    bubble.appendChild(sender);

    const body = document.createElement('div');
    body.className = 'bubble-text';
    body.textContent = text;
    bubble.appendChild(body);

    if (actionInfo) {
      const pill = document.createElement('div');
      pill.className = `action-pill ${actionInfo.success ? '' : 'failed'}`;
      pill.textContent = `${actionInfo.icon || '⚡'} ${actionInfo.message}`;
      bubble.appendChild(pill);
    }

    chatFeed.appendChild(bubble);
    chatFeed.scrollTop = chatFeed.scrollHeight;
  }

  // Detect and update language tag based on input
  function detectAndShowLanguage(text) {
    const hindiRegex = /[\u0900-\u097F]/;
    const bengaliRegex = /[\u0980-\u09FF]/;
    const tamilRegex = /[\u0B80-\u0BFF]/;
    const teluguRegex = /[\u0C00-\u0C7F]/;
    const marathiHinglishKeywords = /\b(kholo|karo|batao|kaisa|kaisi|hai|ho|main|aap|tum|phone|lagao|chalao|madhe|aahe)\b/i;

    let lang = 'English';
    if (hindiRegex.test(text)) lang = 'Hindi (हिंदी)';
    else if (bengaliRegex.test(text)) lang = 'Bengali (বাংলা)';
    else if (tamilRegex.test(text)) lang = 'Tamil (தமிழ்)';
    else if (teluguRegex.test(text)) lang = 'Telugu (తెలుగు)';
    else if (marathiHinglishKeywords.test(text)) lang = 'Hinglish / Hindi';

    detectedLanguage = lang;
    languageTag.style.display = 'inline-flex';
    langName.textContent = lang;
  }

  // --- Action Bridge Implementation ---
  const ActionBridge = {
    openWhatsApp: async function () {
      if (isAndroidNative && typeof nativeBridge.openWhatsApp === 'function') {
        try {
          const res = JSON.parse(nativeBridge.openWhatsApp());
          showActionCard('💬', 'WhatsApp', res.message || 'Opening WhatsApp');
          return res;
        } catch (e) {
          return { success: false, message: 'Native WhatsApp open error: ' + e.message };
        }
      } else {
        // Safe Browser Fallback
        showActionCard('💬', 'WhatsApp (Web)', 'Opening WhatsApp link');
        window.open('https://api.whatsapp.com/send', '_blank');
        return { success: true, message: 'WhatsApp opened via web link.' };
      }
    },

    openApp: async function (appName) {
      if (!appName) return { success: false, message: 'App name missing.' };
      const normalized = appName.trim();

      if (isAndroidNative && typeof nativeBridge.openApp === 'function') {
        try {
          const res = JSON.parse(nativeBridge.openApp(normalized));
          showActionCard('📱', normalized, res.message || `Opening ${normalized}`);
          return res;
        } catch (e) {
          return { success: false, message: 'Native openApp error: ' + e.message };
        }
      } else {
        // Web fallback
        const lower = normalized.toLowerCase();
        if (lower.includes('youtube')) {
          showActionCard('▶️', 'YouTube', 'Opening YouTube in browser');
          window.open('https://www.youtube.com', '_blank');
          return { success: true, message: 'YouTube opened in browser.' };
        } else if (lower.includes('instagram')) {
          showActionCard('📷', 'Instagram', 'Opening Instagram in browser');
          window.open('https://www.instagram.com', '_blank');
          return { success: true, message: 'Instagram opened in browser.' };
        } else if (lower.includes('chrome')) {
          window.open('https://www.google.com', '_blank');
          return { success: true, message: 'Browser opened.' };
        } else {
          return {
            success: false,
            message: `Opening ${normalized} directly requires the Arushi Android app.`
          };
        }
      }
    },

    makeCall: async function (phoneNumber) {
      if (!phoneNumber) return { success: false, message: 'Phone number missing.' };

      if (isAndroidNative && typeof nativeBridge.makeCall === 'function') {
        try {
          const res = JSON.parse(nativeBridge.makeCall(phoneNumber));
          showActionCard('📞', 'Calling ' + phoneNumber, res.message || 'Initiating call');
          return res;
        } catch (e) {
          return { success: false, message: 'Native call error: ' + e.message };
        }
      } else {
        // Web fallback: tel: link
        showActionCard('📞', 'Dialer', 'Opening phone dialer with ' + phoneNumber);
        window.location.href = 'tel:' + encodeURIComponent(phoneNumber);
        return { success: true, message: `Opened dialer with ${phoneNumber}` };
      }
    },

    callContact: async function (contactName) {
      if (!contactName) return { success: false, message: 'Contact name missing.' };

      if (isAndroidNative && typeof nativeBridge.callContact === 'function') {
        try {
          const res = JSON.parse(nativeBridge.callContact(contactName));
          if (res.status === 'CALLING') {
            showActionCard('📞', res.name, res.message);
          } else if (res.status === 'MULTIPLE_MATCHES') {
            showActionCard('👥', 'Multiple Contacts Found', res.message);
          } else if (res.status === 'PERMISSION_REQUIRED') {
            showActionCard('🛡️', 'Permission Required', 'Contacts permission is needed');
            permBanner.style.display = 'flex';
          } else {
            showActionCard('🔍', 'Contact Not Found', res.message);
          }
          return res;
        } catch (e) {
          return { success: false, message: 'Contact search error: ' + e.message };
        }
      } else {
        // Normal browser cannot access device contacts
        return {
          success: false,
          status: 'UNAVAILABLE_ON_WEB',
          message: `Accessing device contacts requires the Arushi Android app. You can say 'Call [phone number]' directly.`
        };
      }
    },

    openUrl: async function (url) {
      if (!url) return { success: false, message: 'URL missing.' };

      if (isAndroidNative && typeof nativeBridge.openUrl === 'function') {
        try {
          const res = JSON.parse(nativeBridge.openUrl(url));
          showActionCard('🔗', 'URL', res.message || `Opened ${url}`);
          return res;
        } catch (e) {
          return { success: false, message: 'Error opening URL: ' + e.message };
        }
      } else {
        window.open(url.startsWith('http') ? url : 'https://' + url, '_blank');
        return { success: true, message: `Opened ${url}` };
      }
    }
  };

  // --- Gemini Tools Definition ---
  const geminiTools = [
    {
      functionDeclarations: [
        {
          name: 'openWhatsApp',
          description: 'Opens WhatsApp application on the user device. Call this whenever the user asks to open WhatsApp (e.g., "WhatsApp kholo", "Open WhatsApp", "WhatsApp open karo", "Can you open WhatsApp?", "WhatsApp chalao", "Open my WhatsApp").'
        },
        {
          name: 'openApp',
          description: 'Opens an application by name or common identifier, such as YouTube, Instagram, Chrome, Settings, Camera, Maps, etc.',
          parameters: {
            type: 'OBJECT',
            properties: {
              appName: {
                type: 'STRING',
                description: 'The name of the app to open, e.g. "YouTube", "Instagram", "Chrome", "Settings", "Camera", "Maps".'
              }
            },
            required: ['appName']
          }
        },
        {
          name: 'makeCall',
          description: 'Initiates a phone call to a specific phone number.',
          parameters: {
            type: 'OBJECT',
            properties: {
              phoneNumber: {
                type: 'STRING',
                description: 'The phone number to dial, e.g. "9876543210" or "+919876543210".'
              }
            },
            required: ['phoneNumber']
          }
        },
        {
          name: 'callContact',
          description: 'Searches device contacts by name or relationship (e.g., "Mom", "Mummy", "Rahul", "Dad", "Priya") and calls them.',
          parameters: {
            type: 'OBJECT',
            properties: {
              contactName: {
                type: 'STRING',
                description: 'The contact name or relationship to call (e.g. "Mom", "Mummy", "Rahul", "Dad").'
              }
            },
            required: ['contactName']
          }
        },
        {
          name: 'openUrl',
          description: 'Opens a web URL in the browser.',
          parameters: {
            type: 'OBJECT',
            properties: {
              url: {
                type: 'STRING',
                description: 'The URL to open, starting with https://'
              }
            },
            required: ['url']
          }
        }
      ]
    }
  ];

  // --- Gemini API Call with Tool Execution ---
  async function sendToGemini(userPrompt) {
    if (!GEMINI_API_KEY) {
      if (isAndroidNative && typeof nativeBridge.getApiKey === 'function') {
        GEMINI_API_KEY = nativeBridge.getApiKey() || '';
      }
    }

    if (!GEMINI_API_KEY) {
      appendMessage('arushi', 'Gemini API key is not configured. Please check your secrets configuration.');
      return;
    }

    // Stop previous audio if speaking
    interruptArushi();

    updateStatus('thinking', 'Arushi is thinking...');
    setStateLabel('Understanding...');

    const systemInstruction = {
      parts: [
        {
          text: `You are Arushi, an intelligent, empathetic, and highly capable Indian AI voice assistant.
Your voice is warm, natural, respectful, and friendly.
Languages & Multilingual Support:
- You seamlessly understand and naturally speak in Hindi, English, Hinglish (blend of Hindi and English), Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, Urdu, and other languages.
- MANDATORY LANGUAGE RULE: ALWAYS respond in the exact same language and dialect that the user is currently speaking!
  - If the user speaks Hindi (e.g. "नमस्ते", "तुम कैसी हो?"), reply in pure, natural Hindi.
  - If the user speaks English (e.g. "Hello Arushi"), reply in clear, friendly English.
  - If the user speaks Hinglish (e.g. "WhatsApp open karo", "Mummy ko call lagao", "Hindi mein baat karo"), reply naturally in Hinglish.
  - If the user switches languages mid-conversation, smoothly switch along with them without asking.
App Control & Function Calling:
- When the user asks to open an app (e.g. "WhatsApp kholo", "Open YouTube", "Instagram chalao", "Open settings"), call the corresponding tool function immediately.
- When the user asks to call someone (e.g. "Call Mom", "Rahul ko call karo", "Call 9876543210"), call the corresponding tool function.
- Do NOT just verbally promise you will do it—YOU MUST EXECUTE THE TOOL!
- Keep verbal responses concise, conversational, and direct for voice output.`
        }
      ]
    };

    // Append to local history
    conversationHistory.push({
      role: 'user',
      parts: [{ text: userPrompt }]
    });

    try {
      const url = `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent?key=${GEMINI_API_KEY}`;
      const payload = {
        contents: conversationHistory,
        systemInstruction: systemInstruction,
        tools: geminiTools
      };

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (!res.ok) {
        throw new Error(`Gemini error HTTP ${res.status}`);
      }

      const data = await res.json();
      const candidate = data.candidates && data.candidates[0];
      if (!candidate || !candidate.content) {
        throw new Error('No candidate content received');
      }

      const modelParts = candidate.content.parts || [];
      conversationHistory.push({
        role: 'model',
        parts: modelParts
      });

      // Check for function calls
      const funcCallPart = modelParts.find(p => p.functionCall);

      if (funcCallPart) {
        const fc = funcCallPart.functionCall;
        const toolName = fc.name;
        const toolArgs = fc.args || {};

        setStateLabel(`Executing ${toolName}...`);
        updateStatus('thinking', `Executing ${toolName}...`);

        let toolOutput = { success: false, message: 'Action not found' };

        if (toolName === 'openWhatsApp') {
          toolOutput = await ActionBridge.openWhatsApp();
        } else if (toolName === 'openApp') {
          toolOutput = await ActionBridge.openApp(toolArgs.appName);
        } else if (toolName === 'makeCall') {
          toolOutput = await ActionBridge.makeCall(toolArgs.phoneNumber);
        } else if (toolName === 'callContact') {
          toolOutput = await ActionBridge.callContact(toolArgs.contactName);
        } else if (toolName === 'openUrl') {
          toolOutput = await ActionBridge.openUrl(toolArgs.url);
        }

        // Send tool response back to Gemini
        const toolResponseContent = {
          role: 'user',
          parts: [
            {
              functionResponse: {
                name: toolName,
                response: {
                  name: toolName,
                  content: toolOutput
                }
              }
            }
          ]
        };

        conversationHistory.push(toolResponseContent);

        const followUpPayload = {
          contents: conversationHistory,
          systemInstruction: systemInstruction,
          tools: geminiTools
        };

        const followUpRes = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(followUpPayload)
        });

        if (followUpRes.ok) {
          const followUpData = await followUpRes.json();
          const followUpCandidate = followUpData.candidates && followUpData.candidates[0];
          if (followUpCandidate && followUpCandidate.content) {
            const followUpParts = followUpCandidate.content.parts || [];
            conversationHistory.push({
              role: 'model',
              parts: followUpParts
            });
            const textPart = followUpParts.find(p => p.text);
            const finalText = textPart ? textPart.text : (toolOutput.message || 'Action executed.');
            appendMessage('arushi', finalText, {
              success: toolOutput.success,
              icon: toolName === 'openWhatsApp' ? '💬' : (toolName.includes('Call') ? '📞' : '📱'),
              message: toolOutput.message
            });
            await playGeminiVoice(finalText);
            return;
          }
        }

        appendMessage('arushi', toolOutput.message || 'Done', {
          success: toolOutput.success,
          message: toolOutput.message
        });
        await playGeminiVoice(toolOutput.message || 'Done');
      } else {
        // Standard conversational response
        const textPart = modelParts.find(p => p.text);
        const replyText = textPart ? textPart.text : 'I am here with you.';
        appendMessage('arushi', replyText);
        await playGeminiVoice(replyText);
      }
    } catch (err) {
      console.error('Gemini error:', err);
      updateStatus('ready', 'Ready');
      setStateLabel('Error communicating with Arushi.');
      appendMessage('arushi', 'Sorry, I encountered an issue. Please try again.');
    }
  }

  // --- Gemini Audio / Voice-to-Voice Generation ---
  async function playGeminiVoice(text) {
    if (!text || !text.trim() || !GEMINI_API_KEY) {
      updateStatus('ready', 'Ready');
      setStateLabel('Tap microphone to speak');
      return;
    }

    updateStatus('speaking', 'Arushi is speaking...');
    setStateLabel('Arushi is speaking...');
    isSpeaking = true;

    try {
      const ttsUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=${GEMINI_API_KEY}`;
      const ttsPayload = {
        contents: [
          {
            parts: [
              {
                text: `Read the following text aloud with a warm, natural female voice: ${text}`
              }
            ]
          }
        ],
        generationConfig: {
          responseModalities: ['AUDIO'],
          speechConfig: {
            voiceConfig: {
              prebuiltVoiceConfig: {
                voiceName: 'Aoede'
              }
            }
          }
        }
      };

      const res = await fetch(ttsUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(ttsPayload)
      });

      if (!res.ok) {
        throw new Error(`TTS HTTP error ${res.status}`);
      }

      const data = await res.json();
      const part = data.candidates?.[0]?.content?.parts?.[0];
      const inlineData = part?.inlineData;

      if (inlineData && inlineData.data) {
        await playPcmBase64(inlineData.data, 24000);
      } else {
        console.warn('No audio inlineData received in TTS response');
        onAudioFinished();
      }
    } catch (e) {
      console.warn('Gemini Audio TTS generation failed:', e);
      onAudioFinished();
    }
  }

  // Decode and play 16-bit 24kHz Linear PCM from base64
  async function playPcmBase64(base64Data, sampleRate = 24000) {
    const ctx = getAudioContext();
    const binary = atob(base64Data);
    const len = binary.length;
    const numSamples = Math.floor(len / 2);
    const float32Array = new Float32Array(numSamples);

    for (let i = 0; i < numSamples; i++) {
      const low = binary.charCodeAt(i * 2);
      const high = binary.charCodeAt(i * 2 + 1);
      let sample = (high << 8) | low;
      if (sample >= 0x8000) sample -= 0x10000;
      float32Array[i] = sample / 32768.0;
    }

    const audioBuffer = ctx.createBuffer(1, numSamples, sampleRate);
    audioBuffer.getChannelData(0).set(float32Array);

    const source = ctx.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(analyser);
    analyser.connect(ctx.destination);

    currentAudioSource = source;

    source.onended = () => {
      if (currentAudioSource === source) {
        currentAudioSource = null;
        onAudioFinished();
      }
    };

    source.start(0);
  }

  function onAudioFinished() {
    isSpeaking = false;
    updateStatus('ready', 'Ready');
    setStateLabel('Tap microphone to speak with Arushi');
  }

  // --- Voice Input (Microphone / Speech Recognition) ---
  function initSpeechRecognition() {
    const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRec) {
      console.warn('SpeechRecognition API not available in this browser');
      return null;
    }

    const rec = new SpeechRec();
    rec.continuous = false;
    rec.interimResults = false;
    rec.maxAlternatives = 1;
    // Don't fixate lang to single locale: allow auto speech recognition or default to en-IN / hi-IN
    rec.lang = 'en-IN';

    rec.onstart = () => {
      isListening = true;
      micBtn.classList.add('recording');
      updateStatus('listening', 'Listening...');
      setStateLabel('Listening... speak now');
    };

    rec.onresult = (event) => {
      const transcript = event.results[0][0].transcript;
      if (transcript && transcript.trim()) {
        detectAndShowLanguage(transcript);
        appendMessage('user', transcript);
        sendToGemini(transcript);
      }
    };

    rec.onerror = (event) => {
      console.warn('Speech recognition error:', event.error);
      stopListening();
      if (event.error === 'not-allowed') {
        setStateLabel('Microphone permission denied. Type below or enable permissions.');
      } else {
        setStateLabel('Could not catch that. Tap mic and try again.');
      }
    };

    rec.onend = () => {
      stopListening();
    };

    return rec;
  }

  function startListening() {
    // If speaking, interrupt!
    if (isSpeaking) {
      interruptArushi();
    }

    // Initialize audio context
    getAudioContext();

    if (!speechRecognition) {
      speechRecognition = initSpeechRecognition();
    }

    if (speechRecognition) {
      try {
        speechRecognition.start();
      } catch (e) {
        console.warn('Could not start speech recognition:', e);
      }
    } else {
      // Fallback: prompt for user text if speech recognition unavailable
      const promptText = prompt('Speech recognition not available. Please enter your voice command:');
      if (promptText) {
        detectAndShowLanguage(promptText);
        appendMessage('user', promptText);
        sendToGemini(promptText);
      }
    }
  }

  function stopListening() {
    isListening = false;
    micBtn.classList.remove('recording');
    if (speechRecognition) {
      try {
        speechRecognition.stop();
      } catch (e) {}
    }
    if (!isSpeaking && !isThinking) {
      updateStatus('ready', 'Ready');
    }
  }

  // Mic Button Toggle
  micBtn.addEventListener('click', () => {
    if (isListening) {
      stopListening();
    } else {
      startListening();
    }
  });

  orbContainer.addEventListener('click', () => {
    if (isSpeaking) {
      interruptArushi();
    } else if (isListening) {
      stopListening();
    } else {
      startListening();
    }
  });

  // Text Input Submission
  inputForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const query = textInput.value.trim();
    if (!query) return;

    textInput.value = '';
    detectAndShowLanguage(query);
    appendMessage('user', query);
    sendToGemini(query);
  });

  // Suggestion Chips
  if (chipsContainer) {
    chipsContainer.addEventListener('click', (e) => {
      const chip = e.target.closest('.chip');
      if (!chip) return;
      const query = chip.dataset.query;
      if (query) {
        detectAndShowLanguage(query);
        appendMessage('user', query);
        sendToGemini(query);
      }
    });
  }

})();
