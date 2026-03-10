/**
 * OpenClaw Glasses Bridge v3
 * Glasses audio → Groq Whisper STT → openclaw agent CLI → reply
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const PORT = 3771;

// Load .env
try {
    fs.readFileSync(path.join(__dirname, '.env'), 'utf8').split('\n').forEach(line => {
        const [k, ...v] = line.split('=');
        if (k && v.length) process.env[k.trim()] = v.join('=').trim();
    });
} catch {}

const GROQ_KEY = process.env.GROQ_API_KEY || '';
console.log(`Groq key: ${GROQ_KEY ? '***' + GROQ_KEY.slice(-4) : 'MISSING'}`);

/** Groq Whisper STT */
function whisperSTT(wavBuffer) {
    return new Promise((resolve, reject) => {
        const boundary = '----B' + Date.now();
        const parts = [];
        parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="a.wav"\r\nContent-Type: audio/wav\r\n\r\n`));
        parts.push(wavBuffer);
        parts.push(Buffer.from(`\r\n--${boundary}\r\nContent-Disposition: form-data; name="model"\r\n\r\nwhisper-large-v3\r\n`));
        parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="language"\r\n\r\nzh\r\n`));
        parts.push(Buffer.from(`--${boundary}--\r\n`));
        const body = Buffer.concat(parts);

        const req = https.request({
            hostname: 'api.groq.com', path: '/openai/v1/audio/transcriptions',
            method: 'POST', headers: {
                'Authorization': `Bearer ${GROQ_KEY}`,
                'Content-Type': `multipart/form-data; boundary=${boundary}`,
                'Content-Length': body.length
            }, timeout: 30000
        }, res => {
            let d = ''; res.on('data', c => d += c);
            res.on('end', () => {
                try { const j = JSON.parse(d); j.text !== undefined ? resolve(j.text) : reject(new Error(d)); }
                catch { reject(new Error(d.substring(0, 200))); }
            });
        });
        req.on('error', reject);
        req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
        req.write(body); req.end();
    });
}

/** Send to OpenClaw via CLI */
function sendToOpenClaw(text) {
    return new Promise((resolve, reject) => {
        try {
            // Use openclaw agent CLI to send message and get reply
            const escaped = text.replace(/"/g, '\\"').replace(/`/g, '\\`');
            const cmd = `openclaw agent --session-id main --message "${escaped}" --json --timeout 120`;
            
            console.log(`[CLI] ${cmd.substring(0, 80)}...`);
            
            const result = execSync(cmd, {
                encoding: 'utf8',
                timeout: 130000,
                windowsHide: true,
                env: { ...process.env, NO_COLOR: '1' }
            });

            try {
                const jsonMatch = result.match(/\{[\s\S]*\}/);
                if (jsonMatch) {
                    const json = JSON.parse(jsonMatch[0]);
                    // Extract from openclaw agent JSON structure
                    const text = json.result?.payloads?.[0]?.text
                        || json.reply || json.message || json.text || json.content;
                    resolve(text || result.trim());
                } else {
                    const clean = result.replace(/\x1b\[[0-9;]*m/g, '').trim();
                    const lines = clean.split('\n').filter(l => !l.includes('OpenClaw') && !l.includes('🦞'));
                    resolve(lines.join('\n').trim() || clean);
                }
            } catch {
                resolve(result.trim());
            }
        } catch (err) {
            const output = err.stdout || err.stderr || err.message;
            reject(new Error(`CLI error: ${output?.substring(0, 300)}`));
        }
    });
}

/** Parse multipart audio */
function parseAudio(req) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        req.on('data', c => chunks.push(c));
        req.on('end', () => {
            const body = Buffer.concat(chunks);
            const boundary = (req.headers['content-type'] || '').match(/boundary=(.+)/)?.[1]?.trim();
            if (!boundary) { reject(new Error('No boundary')); return; }
            const sep = Buffer.from(`--${boundary}`);
            const start = body.indexOf(sep);
            const headerEnd = body.indexOf(Buffer.from('\r\n\r\n'), start);
            const dataStart = headerEnd + 4;
            const nextSep = body.indexOf(sep, dataStart);
            resolve(body.slice(dataStart, nextSep > 0 ? nextSep - 2 : body.length));
        });
        req.on('error', reject);
    });
}

const server = http.createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');

    if (req.method === 'GET' && req.url === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'ok', stt: 'groq-whisper', relay: 'openclaw-cli' }));
        return;
    }

    if (req.method === 'POST' && req.url === '/voice') {
        try {
            const audio = await parseAudio(req);
            console.log(`[🎤] ${audio.length} bytes`);

            const text = await whisperSTT(audio);
            console.log(`[STT] "${text}"`);

            if (!text?.trim()) {
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ reply: '没有听清，请再说一次' }));
                return;
            }

            console.log(`[→ Mia] "${text}"`);
            const reply = await sendToOpenClaw(text);
            console.log(`[← Mia] "${String(reply).substring(0, 100)}"`);

            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ reply: String(reply), transcription: text }));
        } catch (err) {
            console.error('[ERR]', err.message);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: err.message }));
        }
        return;
    }

    if (req.method === 'POST' && req.url === '/message') {
        let body = '';
        req.on('data', c => body += c);
        req.on('end', async () => {
            try {
                const { text } = JSON.parse(body);
                const reply = await sendToOpenClaw(text);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ reply: String(reply) }));
            } catch (err) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    res.writeHead(404); res.end('Not found');
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`\n🕶️ Bridge v3 on :${PORT} | Groq Whisper → OpenClaw CLI\n`);
});
