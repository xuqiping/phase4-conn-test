import { createWriteStream } from 'fs';
import { mkdir } from 'fs/promises';
import { get } from 'https';
import { createGunzip } from 'zlib';
import { spawn } from 'child_process';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const MODELS_DIR = join(ROOT, 'models');

// 使用小模型：~100MB，适合桌面应用
const MODEL_NAME = 'sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16';
const ARCHIVE_NAME = `${MODEL_NAME}.tar.bz2`;

// 下载源（优先 ModelScope 国内镜像）
const MIRRORS = [
  {
    name: 'ModelScope (国内)',
    url: `https://www.modelscope.cn/models/pengzhendong/sherpa-onnx-streaming-zipformer-bilingual-zh-en/resolve/master/${ARCHIVE_NAME}`,
  },
  {
    name: 'GitHub Release',
    url: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${ARCHIVE_NAME}`,
  },
];

function downloadFile(url, dest) {
  return new Promise((resolve, reject) => {
    const proxy = process.env.HTTPS_PROXY || process.env.https_proxy;
    const options = {};
    if (proxy) {
      console.log(`  使用代理: ${proxy}`);
      // 简单代理支持
      const proxyUrl = new URL(proxy);
      options.host = proxyUrl.hostname;
      options.port = proxyUrl.port;
      options.path = url;
      options.headers = { Host: new URL(url).host };
    } else {
      options.path = new URL(url).pathname + new URL(url).search;
      options.host = new URL(url).host;
      options.protocol = new URL(url).protocol;
    }

    const file = createWriteStream(dest);
    let total = 0;
    let lastLog = 0;

    const req = get(url, { timeout: 30000 }, (res) => {
      if (res.statusCode === 301 || res.statusCode === 302) {
        const location = res.headers.location;
        console.log(`  重定向到: ${location}`);
        file.close();
        downloadFile(location, dest).then(resolve).catch(reject);
        return;
      }
      if (res.statusCode !== 200) {
        file.close();
        reject(new Error(`HTTP ${res.statusCode}`));
        return;
      }

      const contentLength = parseInt(res.headers['content-length'] || '0');
      console.log(`  大小: ${(contentLength / 1024 / 1024).toFixed(1)} MB`);

      res.on('data', (chunk) => {
        total += chunk.length;
        const now = Date.now();
        if (now - lastLog > 2000) {
          const pct = contentLength ? `(${(total / contentLength * 100).toFixed(1)}%)` : '';
          process.stdout.write(`\r  已下载: ${(total / 1024 / 1024).toFixed(1)} MB ${pct}    `);
          lastLog = now;
        }
      });

      res.pipe(file);
      file.on('finish', () => {
        process.stdout.write('\n');
        file.close(resolve);
      });
    });

    req.on('error', (err) => {
      file.close();
      reject(err);
    });

    req.on('timeout', () => {
      req.destroy();
      file.close();
      reject(new Error('请求超时'));
    });
  });
}

function extractTarBz2(archive, destDir) {
  return new Promise((resolve, reject) => {
    console.log('正在解压...');
    const tar = spawn('tar', ['xjf', archive, '-C', destDir], {
      stdio: 'inherit',
      shell: true,
    });
    tar.on('close', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`tar 退出码: ${code}`));
    });
    tar.on('error', reject);
  });
}

async function main() {
  console.log('============================================');
  console.log('  语音模型下载工具');
  console.log('  模型: zipformer-small-bilingual-zh-en');
  console.log('============================================\n');

  await mkdir(MODELS_DIR, { recursive: true });

  const archivePath = join(MODELS_DIR, ARCHIVE_NAME);

  // 检查模型是否已存在
  const expectedFiles = ['encoder-epoch-99-avg-1.onnx', 'decoder-epoch-99-avg-1.onnx', 'joiner-epoch-99-avg-1.onnx', 'tokens.txt'];
  const modelSubdir = join(MODELS_DIR, MODEL_NAME);
  const hasModel = expectedFiles.every(f => {
    try {
      const fs = require('fs');
      return fs.existsSync(join(modelSubdir, f));
    } catch { return false; }
  });

  if (hasModel) {
    console.log('模型文件已存在，跳过下载。');
    console.log(`路径: ${modelSubdir}`);
    return;
  }

  // 尝试下载
  for (const mirror of MIRRORS) {
    console.log(`\n尝试从 ${mirror.name} 下载...`);
    console.log(`URL: ${mirror.url}`);
    try {
      await downloadFile(mirror.url, archivePath);
      console.log('下载完成！');
      break;
    } catch (err) {
      console.log(`\n  下载失败: ${err.message}`);
      if (mirror === MIRRORS[MIRRORS.length - 1]) {
        console.error('\n所有镜像下载失败。请检查网络或手动下载模型。');
        console.error(`手动下载地址: ${MIRRORS[1].url}`);
        process.exit(1);
      }
    }
  }

  // 解压
  try {
    await extractTarBz2(archivePath, MODELS_DIR);
    // 删除压缩包
    const fs = await import('fs/promises');
    await fs.unlink(archivePath);
    console.log('\n模型解压完成！');
    console.log(`模型路径: ${modelSubdir}`);
  } catch (err) {
    console.error(`解压失败: ${err.message}`);
    console.error('请确保系统支持 tar 命令。');
    process.exit(1);
  }
}

main().catch(console.error);
