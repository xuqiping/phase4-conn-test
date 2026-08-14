// chunk 光点格：一轮内每个 chunk 一个小光点（完成=绿/进行=呼吸蓝/待做=暗）。
export default function ChunkDots({
  total,
  done,
  active,
}: {
  total: number;
  done: number;
  active: boolean;
}) {
  return (
    <span className="flex flex-wrap gap-1" aria-label={`${done}/${total} chunks`}>
      {Array.from({ length: total }, (_, i) => (
        <span
          key={i}
          className={`size-2 rounded-full ${
            i < done
              ? "bg-success"
              : active && i === done
                ? "animate-pulse bg-brand2"
                : "bg-border-strong"
          }`}
        />
      ))}
    </span>
  );
}
