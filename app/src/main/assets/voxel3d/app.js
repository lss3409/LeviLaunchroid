// LBBL 3D 体素视图（WebGL / three.js r128）——数据由 Android 端
// VoxelBridge.getData() 注入（textures=base64 贴图，blocks=表面方块）。
// 贴图分组 InstancedMesh + OrbitControls，导出 PNG 经 VoxelBridge.exported。
(function () {
  var container = document.getElementById('container');
  var loading = document.getElementById('loading');
  var renderer, scene, camera, controls;
  var blockSize = 8;

  function loadData() {
    console.log('loadData attempt');
    var data = null;
    try {
      var raw = window.VoxelBridge.getData();
      console.log('getData len=' + (raw ? raw.length : 'null'));
      data = JSON.parse(raw);
    } catch (e) {
      console.error('getData failed: ' + e);
    }
    if (!data || !data.blocks || !data.blocks.length) {
      // 数据未就绪（Android 端还在生成）——稍后重试
      setTimeout(loadData, 300);
      return;
    }
    buildScene(data);
  }

  function buildScene(data) {
    loading.style.display = 'none';
    var size = data.size || 64;
    console.log('voxel data: size=' + size + ' blocks=' + data.blocks.length
        + ' textures=' + data.textures.length);
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
    } catch (e) {
      console.error('WebGL init failed: ' + e);
      loading.textContent = 'WebGL init failed';
      loading.style.display = 'flex';
      return;
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setSize(container.clientWidth, container.clientHeight);
    renderer.setClearColor(0x12141A, 1);
    // sRGB 输出（否则 sRGB 贴图在线性空间整体发暗发灰=「灰板」）
    renderer.outputEncoding = THREE.sRGBEncoding;
    container.appendChild(renderer.domElement);

    scene = new THREE.Scene();
    scene.background = new THREE.Color(0x12141A);

    // 布局未完成时 container 尺寸可能为 0（aspect NaN=投影畸形）
    var cw = container.clientWidth || window.innerWidth || 1;
    var ch = container.clientHeight || window.innerHeight || 1;
    camera = new THREE.PerspectiveCamera(45, cw / ch, 0.1, 20000);
    camera.position.set(12, 12, 12);
    camera.lookAt(0, 0, 0);
    // v0.4.214：照搬 schematic-manager——材质 MeshBasic（光照无关），
    // 立体感与染色由 Java 端预乘的面明暗/方块染色承载

    // atlas 单张图集加载完成后建网格（每个面独立克隆纹理设 UV——
    // 顶面=top 贴图、侧面=side 贴图、底面=side）
    var img = new Image();
    img.onload = function () {
      var atlasTex = new THREE.CanvasTexture(img);
      atlasTex.magFilter = THREE.NearestFilter;
      atlasTex.minFilter = THREE.NearestFilter;
      atlasTex.generateMipmaps = false;
      atlasTex.encoding = THREE.sRGBEncoding;
      atlasTex.needsUpdate = true;
      buildMeshes(data, atlasTex, size);
    };
    img.onerror = function () {
      console.error('atlas load failed');
      buildMeshes(data, null, size);
    };
    img.src = 'data:image/png;base64,' + data.atlas;
  }

  function buildMeshes(data, atlasTex, size) {
    try {
      // blocks 是紧凑数组 [x,y,z,t]（Java 端为省体积不输出对象）
      var groups = {};
      for (var i = 0; i < data.blocks.length; i++) {
        var b = data.blocks[i];
        var t = b[3];
        (groups[t] = groups[t] || []).push(b);
      }
      var boxGeo = new THREE.BoxGeometry(blockSize, blockSize, blockSize);
      var half = size / 2;
      var yHalf = (data.ySize || size) / 2;
      var matrix = new THREE.Matrix4();
      var atlasPx = data.atlasGrid * 16;
      var cell = 16.0 / atlasPx;
      // UV 变体纹理（克隆共享图集图像，独立 offset/repeat）
      function uvTex(uv) {
        var t = atlasTex.clone();
        t.needsUpdate = true;
        t.offset.set(uv[0] / atlasPx, 1 - (uv[1] + 16) / atlasPx);
        t.repeat.set(cell, cell);
        return t;
      }
      var bounds = new THREE.Box3();
      var allPos = [];
      for (var key in groups) {
        var list = groups[key];
        var ti = data.textures[key];
        var boxMaterials;
        if (atlasTex && ti) {
          // MeshBasic + 预乘明暗变体（顶面 ti.t / 侧面 ti.s / 底面 ti.b）
          var topMat = new THREE.MeshBasicMaterial({ map: uvTex(ti.t) });
          var sideMat = new THREE.MeshBasicMaterial({ map: uvTex(ti.s) });
          var bottomMat = new THREE.MeshBasicMaterial({ map: uvTex(ti.b || ti.s) });
          // BoxGeometry 面顺序：+X,-X,+Y,-Y,+Z,-Z
          boxMaterials = [sideMat, sideMat, topMat, bottomMat, sideMat, sideMat];
        } else {
          var grey = new THREE.MeshBasicMaterial({ color: 0x8A8A8A });
          boxMaterials = grey;
        }
        var mesh = new THREE.InstancedMesh(boxGeo, boxMaterials, list.length);
        for (var i = 0; i < list.length; i++) {
          var b = list[i];
          var px = (b[0] - half) * blockSize,
              py = (b[1] - yHalf) * blockSize,
              pz = (b[2] - half) * blockSize;
          matrix.makeTranslation(px, py, pz);
          mesh.setMatrixAt(i, matrix);
          bounds.expandByPoint(new THREE.Vector3(px, py, pz));
          allPos.push({ x: px, y: py, z: pz });
        }
        mesh.instanceMatrix.needsUpdate = true;
        scene.add(mesh);
      }
      console.log('meshes built: ' + Object.keys(groups).length + ' groups');

      // v0.4.214：照搬 schematic-manager 的取景——投影拟合距离
      // （8 角点投影到相机横/纵轴解距离下界，margin 1.08；细长结构
      // 不再被对角线坑成「绕大圈/显示不全」）+ 默认视角 (-1,-1,-1)
      // 锚点=上部 50% 方块的质心（树冠视觉中心）：树干细长会把
      // 全体质心拉到树干中段，树冠（视觉主体）偏到画面右上，
      // 「越缩放越往右上跑」的根因——只统计上半部分的方块
      var yMid = (bounds.min.y + bounds.max.y) / 2;
      var centroid = new THREE.Vector3();
      var upperCount = 0;
      for (var i = 0; i < allPos.length; i++) {
        var p = allPos[i];
        if (p.y >= yMid) {
          centroid.x += p.x;
          centroid.y += p.y;
          centroid.z += p.z;
          upperCount++;
        }
      }
      if (upperCount === 0) {
        centroid.copy(bounds.getCenter(new THREE.Vector3()));
      } else {
        centroid.divideScalar(upperCount);
      }
      // 往下抬一点：上部质心偏树冠，长条结构底部贴画面下缘——靶心
      // 下移包围盒高度的 6%（待做：按结构上下分布自适应）
      centroid.y -= (bounds.max.y - bounds.min.y) * 0.06;
      var bcenter = centroid.clone();
      var viewDir = new THREE.Vector3(-1, -1, -1).normalize();
      var fitDist = fitDistance(bounds, viewDir, camera.fov, camera.aspect);
      // 相机在 dir 反方向（照搬 frameBox：center + dir·(-dist)——
      // 漏负号=相机跑到拟合假设相反侧=结构偏移到画面角落的根因）
      camera.position.copy(bcenter).addScaledVector(viewDir, -fitDist);
      camera.lookAt(bcenter);
      camera.near = Math.max(fitDist / 100, 0.02);
      camera.far = Math.max(fitDist * 12, 100);
      camera.updateProjectionMatrix();

      controls = new THREE.OrbitControls(camera, renderer.domElement);
      controls.target.copy(bcenter);
      // v0.4.214：恢复双指平移（「绕大圈」的真凶是 viewport 被乘两遍
      // 导致画面错位——已修复，pan 可放心恢复）
      controls.enablePan = true;
      controls.enableDamping = true;
      controls.dampingFactor = 0.1;
      var zl = zoomLimits(fitDist);
      controls.minDistance = zl.min;
      controls.maxDistance = zl.max;
      controls.maxPolarAngle = Math.PI * 0.55;

      // 烤串轴可视化：一根垂直轴穿过旋转靶心 bcenter（从包围盒底到顶），
      // 两端小球标记轴端点——直接目测「轴」与「结构视觉中心」是否重合
      var axisH = bounds.max.y - bounds.min.y + 10;
      var axisCyl = new THREE.Mesh(
          new THREE.CylinderGeometry(0.45, 0.45, axisH, 6),
          new THREE.MeshBasicMaterial({ color: 0xFF4B4B, transparent: true, opacity: 0.95 }));
      axisCyl.position.set(bcenter.x, (bounds.min.y + bounds.max.y) / 2, bcenter.z);
      scene.add(axisCyl);
      var axisDotGeo = new THREE.SphereGeometry(1.4, 10, 8);
      var dotTop = new THREE.Mesh(axisDotGeo,
          new THREE.MeshBasicMaterial({ color: 0xFFD24B }));
      dotTop.position.set(bcenter.x, bounds.max.y + 3, bcenter.z);
      scene.add(dotTop);
      var dotBot = new THREE.Mesh(axisDotGeo,
          new THREE.MeshBasicMaterial({ color: 0xFFD24B }));
      dotBot.position.set(bcenter.x, bounds.min.y - 3, bcenter.z);
      scene.add(dotBot);

      // 诊断：结构中心投影到 NDC 与 CSS 屏幕像素（区分「相机几何偏」
      // 与「渲染视口偏」：投影像素应=容器中心）
      var ndc = bcenter.clone().project(camera);
      var scx = (ndc.x + 1) / 2 * container.clientWidth;
      var scy = (1 - ndc.y) / 2 * container.clientHeight;
      console.log('diag: bcenter=' + bcenter.x.toFixed(1) + ',' + bcenter.y.toFixed(1) + ','
          + bcenter.z.toFixed(1) + ' camPos=' + camera.position.x.toFixed(1) + ','
          + camera.position.y.toFixed(1) + ',' + camera.position.z.toFixed(1)
          + ' fitDist=' + fitDist.toFixed(1) + ' ndc=' + ndc.x.toFixed(3) + ','
          + ndc.y.toFixed(3) + ' screen=' + scx.toFixed(0) + ',' + scy.toFixed(0)
          + ' canvas=' + renderer.domElement.width + 'x' + renderer.domElement.height
          + ' css=' + renderer.domElement.clientWidth + 'x' + renderer.domElement.clientHeight
          + ' container=' + container.clientWidth + 'x' + container.clientHeight
          + ' window=' + window.innerWidth + 'x' + window.innerHeight);
      // 操作级诊断：触摸起止 + 每 60 帧相机姿态
      var dom = renderer.domElement;
      dom.addEventListener('touchstart', function () {
        console.log('op touchstart pos=' + camera.position.x.toFixed(1) + ','
            + camera.position.y.toFixed(1) + ',' + camera.position.z.toFixed(1)
            + ' tgt=' + controls.target.x.toFixed(1) + ',' + controls.target.y.toFixed(1)
            + ',' + controls.target.z.toFixed(1));
      });
      dom.addEventListener('touchend', function () {
        console.log('op touchend pos=' + camera.position.x.toFixed(1) + ','
            + camera.position.y.toFixed(1) + ',' + camera.position.z.toFixed(1)
            + ' tgt=' + controls.target.x.toFixed(1) + ',' + controls.target.y.toFixed(1)
            + ',' + controls.target.z.toFixed(1));
      });
      var frameN = 0;
      var origAnimate = animate;
      animate = function () {
        requestAnimationFrame(animate);
        if (controls) controls.update();
        if (renderer) {
          renderer.render(scene, camera);
          if ((frameN++ % 60) === 0) {
            // 渲染循环里重测 bcenter 投影（矩阵已被 render 更新——与
            // animate 前的 diag 对比：若两者不同说明相机状态被改过）
            var ndc2 = bcenter.clone().project(camera);
            var scx2 = (ndc2.x + 1) / 2 * container.clientWidth;
            var scy2 = (1 - ndc2.y) / 2 * container.clientHeight;
            console.log('op frame pos=' + camera.position.x.toFixed(1) + ','
                + camera.position.y.toFixed(1) + ',' + camera.position.z.toFixed(1)
                + ' tgt=' + controls.target.x.toFixed(1) + ',' + controls.target.y.toFixed(1)
                + ',' + controls.target.z.toFixed(1)
                + ' ndc2=' + ndc2.x.toFixed(3) + ',' + ndc2.y.toFixed(3)
                + ' sc2=' + scx2.toFixed(0) + ',' + scy2.toFixed(0));
          }
          // 第 120 帧（约 2s，用户操作前）自动导出画面到 files/voxel_frame.png
          if (frameN === 120) {
            try {
              var frm = renderer.domElement.toDataURL('image/png').split(',')[1];
              window.VoxelBridge.debugSave(frm);
            } catch (e) {
              console.error('autosave failed: ' + e);
            }
            // Canvas DOM 显示矩形 vs 容器矩形——WebGL 像素居中而肉眼偏
            // 的候选根因：canvas 的 CSS 显示区与容器不一致（参考项目
            // 作者踩过：容器 862px、canvas 显示 987px）
            var cr = container.getBoundingClientRect();
            var cav = renderer.domElement.getBoundingClientRect();
            var rsz = renderer.getSize(new THREE.Vector2());
            var dbs = renderer.getDrawingBufferSize(new THREE.Vector2());
            var glp = null;
            try {
              glp = renderer.getContext().getParameter(renderer.getContext().VIEWPORT);
            } catch (e2) {}
            console.log('diag rect: container=' + cr.left.toFixed(1) + ',' + cr.top.toFixed(1)
                + ' ' + cr.width.toFixed(1) + 'x' + cr.height.toFixed(1)
                + ' canvas=' + cav.left.toFixed(1) + ',' + cav.top.toFixed(1)
                + ' ' + cav.width.toFixed(1) + 'x' + cav.height.toFixed(1)
                + ' offset=' + (cav.left - cr.left).toFixed(1) + ',' + (cav.top - cr.top).toFixed(1)
                + ' rsz=' + rsz.x + 'x' + rsz.y + ' dbs=' + dbs.x + 'x' + dbs.y
                + ' dpr=' + (window.devicePixelRatio || 0)
                + ' glvp=' + (glp ? glp[0] + ',' + glp[1] + ' ' + glp[2] + 'x' + glp[3] : 'n/a'));
          }
        }
      };

      drawAxes();
      startAxesLoop();
      animate();
    } catch (e) {
      console.error('buildMeshes failed: ' + e);
      loading.textContent = 'Render failed: ' + e;
      loading.style.display = 'flex';
    }
  }

  // 投影拟合距离（照搬 schematic-manager fitDistance——8 角点投影到
  // 相机横/纵轴解距离下界，细长结构完整入画且不浪费留白）
  function fitDistance(box, dir, fovDeg, aspect, margin) {
    margin = margin || 1.08;
    var center = box.getCenter(new THREE.Vector3());
    var forward = dir.clone().normalize();
    var upHint = Math.abs(forward.y) > 0.99
        ? new THREE.Vector3(0, 0, 1) : new THREE.Vector3(0, 1, 0);
    var right = new THREE.Vector3().crossVectors(forward, upHint).normalize();
    var up = new THREE.Vector3().crossVectors(right, forward).normalize();
    var tanY = Math.tan(fovDeg * Math.PI / 360) / margin;
    var tanX = tanY * Math.max(aspect, 1e-3);
    var dist = 0;
    var v = new THREE.Vector3();
    for (var i = 0; i < 8; i++) {
      v.set(
          i & 1 ? box.max.x : box.min.x,
          i & 2 ? box.max.y : box.min.y,
          i & 4 ? box.max.z : box.min.z).sub(center);
      var x = v.dot(right);
      var y = v.dot(up);
      var z = v.dot(forward);
      dist = Math.max(dist, Math.abs(y) / tanY - z, Math.abs(x) / tanX - z);
    }
    return Math.max(dist, 1e-3);
  }

  // 缩放区间随自适应距离（照搬 schematic-manager zoomLimits）
  function zoomLimits(fitDist) {
    return {
      min: Math.max(fitDist * 0.06, 0.05),
      max: Math.max(fitDist * 6, 1),
    };
  }

  // 左下角 XYZ 坐标轴（皮肤 3D 预览同款——独立 2D canvas 等距投影，
  // 100ms 循环读相机位联动；不再碰 setViewport/scissor，彻底告别
  // 「viewport 被乘两遍=画面错位」一类 GL 状态污染）
  function drawAxes() {
    var a = document.getElementById('axes');
    if (!a) return;
    var g = a.getContext('2d');
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, 72, 72);
    g.translate(28, 46);
    var px = 0, py = 12, pz = 42, tx = 0, ty = 0, tz = 0;
    if (camera) {
      var p = camera.position;
      px = p.x; py = p.y; pz = p.z;
      if (controls && controls.target) {
        tx = controls.target.x; ty = controls.target.y; tz = controls.target.z;
      }
    }
    // 前向=相机→靶心（皮肤版相机绕原点，f 指向原点）
    var fx = tx - px, fy = ty - py, fz = tz - pz;
    var pl = Math.sqrt(fx * fx + fy * fy + fz * fz) || 1;
    fx /= pl; fy /= pl; fz /= pl;
    var rx = -fz, rz = fx;               // cross(f, (0,1,0))
    var rl = Math.sqrt(rx * rx + rz * rz) || 1;
    rx /= rl; rz /= rl;
    var ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;  // 上 = cross(r, f)
    var k = Math.cos(Math.PI / 6), j = Math.sin(Math.PI / 6), len = 20;
    function proj(vx, vy, vz) {
      var cx = vx * rx + vz * rz;
      var cy = vx * ux + vy * uy + vz * uz;
      var cz = vx * fx + vy * fy + vz * fz;
      return [(cx + cz) * k * len, (-cy + (cx - cz) * j) * len];
    }
    var X = proj(1, 0, 0), Y = proj(0, 1, 0), Z = proj(0, 0, 1);
    g.lineWidth = 2;
    g.strokeStyle = '#E0564B'; g.beginPath(); g.moveTo(0, 0); g.lineTo(X[0], X[1]); g.stroke();
    g.strokeStyle = '#4CAF50'; g.beginPath(); g.moveTo(0, 0); g.lineTo(Y[0], Y[1]); g.stroke();
    g.strokeStyle = '#5C8DD8'; g.beginPath(); g.moveTo(0, 0); g.lineTo(Z[0], Z[1]); g.stroke();
    g.font = 'bold 10px sans-serif';
    g.fillStyle = '#E0564B'; g.fillText('X', X[0] * 1.3, X[1] * 1.3 + 3);
    g.fillStyle = '#4CAF50'; g.fillText('Y', Y[0] * 1.3 - 3, Y[1] * 1.3);
    g.fillStyle = '#5C8DD8'; g.fillText('Z', Z[0] * 1.3, Z[1] * 1.3 + 3);
  }

  var axesLoop = null;
  function startAxesLoop() {
    if (axesLoop) return;
    axesLoop = setInterval(drawAxes, 100);
  }

  function animate() {
    requestAnimationFrame(animate);
    if (renderer) {
      if (controls) controls.update();
      renderer.render(scene, camera);
    }
  }

  window.exportVoxelPng = function () {
    if (!renderer) return;
    var b64 = renderer.domElement.toDataURL('image/png').split(',')[1];
    try { window.VoxelBridge.exported(b64); } catch (e) {}
  };

  window.addEventListener('resize', function () {
    if (!renderer || !camera) return;
    camera.aspect = container.clientWidth / container.clientHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(container.clientWidth, container.clientHeight);
  });

  loadData();
})();
