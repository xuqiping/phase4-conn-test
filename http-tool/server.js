const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 3456;

function serveFile(res, filePath, contentType) {
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('File not found: ' + filePath);
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(data);
  });
}

function parseBody(req, callback) {
  let body = '';
  req.on('data', chunk => (body += chunk));
  req.on('end', () => callback(body));
}

function proxyRequest(req, res) {
  parseBody(req, rawBody => {
    try {
      const payload = JSON.parse(rawBody || '{}');
      const fullUrl = payload.url;
      const method = (payload.method || 'GET').toUpperCase();
      const customHeaders = payload.headers || {};
      const requestBody = payload.body;

      if (!fullUrl) {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: '缺少 url 参数' }));
        return;
      }

      const targetUrl = new URL(fullUrl);
      const options = {
        protocol: targetUrl.protocol,
        hostname: targetUrl.hostname,
        port: targetUrl.port || (targetUrl.protocol === 'https:' ? 443 : 80),
        path: targetUrl.pathname + targetUrl.search,
        method: method,
        headers: {
          'User-Agent': 'file-keeper-http-tool/1.0'
        }
      };

      // 合并用户传入的 headers，跳过 Host（由 Node 自动设置）
      Object.entries(customHeaders).forEach(([key, value]) => {
        if (key.toLowerCase() !== 'host' && value != null && value !== '') {
          options.headers[key] = value;
        }
      });

      const clientModule = targetUrl.protocol === 'https:' ? require('https') : http;
      const proxyReq = clientModule.request(options, proxyRes => {
        let responseBody = '';
        proxyRes.setEncoding('utf8');
        proxyRes.on('data', chunk => (responseBody += chunk));
        proxyRes.on('end', () => {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(
            JSON.stringify({
              status: proxyRes.statusCode,
              statusText: proxyRes.statusMessage,
              headers: proxyRes.headers,
              body: responseBody
            })
          );
        });
      });

      proxyReq.on('error', err => {
        res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: '请求目标失败: ' + err.message }));
      });

      if (requestBody && ['POST', 'PUT', 'PATCH'].includes(method)) {
        proxyReq.write(requestBody);
      }

      proxyReq.end();
    } catch (err) {
      res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: '参数解析失败: ' + err.message }));
    }
  });
}

const server = http.createServer((req, res) => {
  // 允许浏览器跨域访问本工具页面
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(200);
    res.end();
    return;
  }

  const pathname = req.url.split('?')[0];

  if (pathname === '/' || pathname === '/index.html') {
    serveFile(res, path.join(__dirname, 'index.html'), 'text/html; charset=utf-8');
  } else if (pathname === '/api/proxy' && req.method === 'POST') {
    proxyRequest(req, res);
  } else {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not Found');
  }
});

server.listen(PORT, () => {
  console.log(`========================================`);
  console.log(`File Keeper HTTP 调试工具已启动`);
  console.log(`访问地址: http://localhost:${PORT}`);
  console.log(`按 Ctrl+C 停止`);
  console.log(`========================================`);
});
