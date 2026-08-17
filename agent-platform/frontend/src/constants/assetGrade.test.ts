import { describe, expect, it } from 'vitest'
import { ASSET_GRADES, ASSET_GRADE_RANGE, gradeFromScore } from './assetGrade'

/**
 * 前后端等级映射对齐单测（2x#7）。
 * 镜像后端 AssetGradeTest 边界表——两边任一改档位，此测试即红，强制同步。
 */
describe('assetGrade 常量与后端 AssetGrade.java 对齐', () => {
  it('档位边界与后端一致（94/95/89/90/79/80/69/两端/未评）', () => {
    expect(gradeFromScore(null)).toBeNull()
    expect(gradeFromScore(undefined)).toBeNull()
    expect(gradeFromScore(100)).toBe('A+')
    expect(gradeFromScore(95)).toBe('A+')
    expect(gradeFromScore(94)).toBe('A')
    expect(gradeFromScore(90)).toBe('A')
    expect(gradeFromScore(89)).toBe('B')
    expect(gradeFromScore(80)).toBe('B')
    expect(gradeFromScore(79)).toBe('C')
    expect(gradeFromScore(70)).toBe('C')
    expect(gradeFromScore(69)).toBe('D')
    expect(gradeFromScore(0)).toBe('D')
  })

  it('等级区间表与后端 rangeOf 一致', () => {
    expect(ASSET_GRADE_RANGE['A+']).toEqual([95, 100])
    expect(ASSET_GRADE_RANGE.A).toEqual([90, 94])
    expect(ASSET_GRADE_RANGE.B).toEqual([80, 89])
    expect(ASSET_GRADE_RANGE.C).toEqual([70, 79])
    expect(ASSET_GRADE_RANGE.D).toEqual([0, 69])
  })

  it('区间自洽：区间内任一分（含两端）映射回同等级；等级顺序高→低', () => {
    for (const g of ASSET_GRADES) {
      const [min, max] = ASSET_GRADE_RANGE[g]
      expect(gradeFromScore(min)).toBe(g)
      expect(gradeFromScore(max)).toBe(g)
    }
    expect([...ASSET_GRADES]).toEqual(['A+', 'A', 'B', 'C', 'D'])
  })
})
