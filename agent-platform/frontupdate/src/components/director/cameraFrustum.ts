/**
 * 机位视锥可视化 helper：线框金字塔（position → 目标方向 far 面四角）+ 机身小方块。
 * 仅导演视角显示；userData.cameraId 供 raycast 点选联动面板。
 */
import * as THREE from 'three';
import { ASPECT_RATIOS, type DirectorCameraData } from '../../director/sceneModel';

const FRUSTUM_COLOR = 0x35d0ff;
/** 视锥展示长度（米）——纯示意，不参与成像 */
const VIZ_DISTANCE = 6;

export function buildCameraFrustum(cam: DirectorCameraData): THREE.Group {
  const group = new THREE.Group();
  group.name = `frustum-${cam.id}`;
  group.userData.cameraId = cam.id;

  const position = new THREE.Vector3(cam.position[0], cam.position[1], cam.position[2]);
  const target = new THREE.Vector3(cam.target[0], cam.target[1], cam.target[2]);
  const dir = target.clone().sub(position).normalize();
  if (dir.lengthSq() < 1e-8) dir.set(0, 0, -1);

  // 正交基：forward/右/上
  const upHint = Math.abs(dir.y) > 0.99 ? new THREE.Vector3(1, 0, 0) : new THREE.Vector3(0, 1, 0);
  const right = new THREE.Vector3().crossVectors(dir, upHint).normalize();
  const up = new THREE.Vector3().crossVectors(right, dir).normalize();

  const [rw, rh] = ASPECT_RATIOS[cam.aspect];
  const halfH = Math.tan((cam.fov * Math.PI) / 360) * VIZ_DISTANCE;
  const halfW = halfH * (rw / rh);

  const far = position.clone().add(dir.clone().multiplyScalar(VIZ_DISTANCE));
  const corners = [
    far.clone().add(right.clone().multiplyScalar(halfW)).add(up.clone().multiplyScalar(halfH)),
    far.clone().add(right.clone().multiplyScalar(-halfW)).add(up.clone().multiplyScalar(halfH)),
    far.clone().add(right.clone().multiplyScalar(-halfW)).add(up.clone().multiplyScalar(-halfH)),
    far.clone().add(right.clone().multiplyScalar(halfW)).add(up.clone().multiplyScalar(-halfH)),
  ];

  const pts: number[] = [];
  corners.forEach((c) => {
    pts.push(position.x, position.y, position.z, c.x, c.y, c.z);
  });
  for (let i = 0; i < 4; i++) {
    const a = corners[i];
    const b = corners[(i + 1) % 4];
    pts.push(a.x, a.y, a.z, b.x, b.y, b.z);
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3));
  const lines = new THREE.LineSegments(
    geo,
    new THREE.LineBasicMaterial({ color: FRUSTUM_COLOR, transparent: true, opacity: 0.75 }),
  );
  group.add(lines);

  // 机身小方块（点选热区加大：raycast 命中 invisible box 比 line 容易）
  const body = new THREE.Mesh(
    new THREE.BoxGeometry(0.3, 0.22, 0.4),
    new THREE.MeshBasicMaterial({ color: FRUSTUM_COLOR, transparent: true, opacity: 0.35 }),
  );
  body.position.copy(position);
  body.lookAt(target);
  body.userData.cameraId = cam.id;
  group.add(body);
  group.userData.pickMesh = body;

  return group;
}

export function disposeCameraFrustum(group: THREE.Group): void {
  group.traverse((obj) => {
    const line = obj as THREE.LineSegments;
    if (line.geometry) line.geometry.dispose();
    const mat = (obj as THREE.Mesh).material as THREE.Material | undefined;
    if (mat) mat.dispose();
  });
  group.clear();
}
