// P01 Step 3：三栏骨架装配。顶栏在上，左导航 / 中栏视图 / 右栏五 Tab 在下。
import Center from "./components/layout/Center";
import Rightbar from "./components/layout/Rightbar";
import Sidebar from "./components/layout/Sidebar";
import Topbar from "./components/layout/Topbar";
import { useUiStore } from "./stores/ui";

export default function App() {
  const density = useUiStore((s) => s.density);

  return (
    <div className="flex h-screen flex-col" data-density={density}>
      <Topbar />
      <div className="flex min-h-0 flex-1">
        <Sidebar />
        <Center />
        <Rightbar />
      </div>
    </div>
  );
}
