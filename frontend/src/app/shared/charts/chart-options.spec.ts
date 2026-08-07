import {
  AXIS_STRIP,
  asPercentage,
  barKeysInRenderOrder,
  buildBarOption,
  buildStackedOption,
  logAvailable,
  rowKeysInRenderOrder,
  sortBars,
  sortRows,
} from './chart-options';
import type { BarDatum, StackedRow, StackedSeries } from './chart.model';

// echarts draws to a canvas, which jsdom cannot see. So the decisions live in pure functions and
// these specs assert what those functions return — never rendered pixels (ADR 0008, mitigation 2).

const BARS: BarDatum[] = [
  { key: 'rationale', label: 'Rationale', value: 3 },
  { key: 'verif', label: 'Verification Method', value: 12 },
  { key: 'prio', label: 'REQ. Priorität', value: 12 },
];

const SERIES: StackedSeries[] = [
  { key: 'clean', label: 'No findings', token: 'sec-highlight-verified' },
  { key: 'bad', label: 'Mandatory attribute empty', token: 'sec-highlight-undefined' },
];

const ROWS: StackedRow[] = [
  { key: 'a', label: 'Segment', values: { clean: 90, bad: 10 } },
  { key: 'b', label: 'Aircraft', values: { clean: 5, bad: 5 } },
];

describe('sortBars', () => {
  it('puts the worst first by default', () => {
    expect(sortBars(BARS, 'value').map((bar) => bar.label)).toEqual([
      // A tie falls back to the label, so the order is total rather than dependent on input order.
      'REQ. Priorität',
      'Verification Method',
      'Rationale',
    ]);
  });

  it('sorts by name when asked', () => {
    expect(sortBars(BARS, 'name').map((bar) => bar.label)).toEqual([
      'Rationale',
      'REQ. Priorität',
      'Verification Method',
    ]);
  });

  it('does not mutate the input', () => {
    const original = [...BARS];
    sortBars(BARS, 'value');
    expect(BARS).toEqual(original);
  });
});

describe('sortRows', () => {
  it('ranks a row by the total across every series', () => {
    expect(sortRows(ROWS, SERIES, 'value').map((row) => row.label)).toEqual(['Segment', 'Aircraft']);
  });

  it('sorts by name when asked', () => {
    expect(sortRows(ROWS, SERIES, 'name').map((row) => row.label)).toEqual(['Aircraft', 'Segment']);
  });
});

describe('logAvailable', () => {
  // An echarts log axis silently drops non-positive values: a module with zero violations would
  // vanish from the chart rather than sit at the bottom of it, which reads as missing data.
  it('is refused when any value is zero', () => {
    expect(logAvailable([4, 0, 9])).toBe(false);
  });

  it('is refused for an empty series', () => {
    expect(logAvailable([])).toBe(false);
  });

  it('is offered when every value is above zero', () => {
    expect(logAvailable([4, 1, 9])).toBe(true);
  });
});

describe('asPercentage', () => {
  it('is a share of the given total, to one decimal', () => {
    expect(asPercentage(1, 3)).toBe(33.3);
  });

  // A zero denominator is an empty module, not an error, and 0% is the honest answer.
  it('is zero when the total is zero', () => {
    expect(asPercentage(0, 0)).toBe(0);
  });
});

describe('buildBarOption', () => {
  const build = (overrides: Partial<Parameters<typeof buildBarOption>[0]> = {}) =>
    buildBarOption({
      data: BARS,
      sort: 'value',
      scale: 'linear',
      mode: 'absolute',
      total: 100,
      token: 'sec-blue-mid',
      valueName: 'Violations',
      ...overrides,
    });

  it('feeds the data reversed so the largest bar reads at the top', () => {
    const option = build() as { series: { data: number[] }[] };
    expect(option.series[0].data).toEqual([3, 12, 12]);
  });

  it('labels the category axis in the same reversed order as the values', () => {
    const option = build() as { yAxis: { data: string[] }; series: { data: number[] }[] };
    expect(option.yAxis.data).toEqual(['Rationale', 'Verification Method', 'REQ. Priorität']);
    expect(option.series[0].data).toEqual([3, 12, 12]);
  });

  it('converts to percentages of the given total', () => {
    const option = build({ mode: 'percentage' }) as { series: { data: number[] }[] };
    expect(option.series[0].data).toEqual([3, 12, 12]);
  });

  it('uses a log axis when asked and every value allows it', () => {
    const option = build({ scale: 'log' }) as { xAxis: { type: string } };
    expect(option.xAxis.type).toBe('log');
  });

  it('degrades to linear rather than dropping a zero-valued bar', () => {
    const withZero = [...BARS, { key: 'z', label: 'Zero', value: 0 }];
    const option = build({ scale: 'log', data: withZero }) as { xAxis: { type: string } };
    expect(option.xAxis.type).toBe('value');
  });

  // The axis name is placed against the container and `containLabel` measures only tick labels, so
  // the room below the plot area and the distance the name is placed at have to be the same number.
  // At `nameLocation: 'end'` it was neither — it sat past the last tick and off the right edge.
  it('centres the axis name in the strip it reserved for it', () => {
    const option = build() as {
      grid: { bottom: number };
      xAxis: { nameLocation: string; nameGap: number };
    };
    expect(option.xAxis.nameLocation).toBe('middle');
    expect(option.xAxis.nameGap).toBe(AXIS_STRIP);
    expect(option.grid.bottom).toBe(AXIS_STRIP);
  });
});

describe('buildStackedOption', () => {
  const build = (overrides: Partial<Parameters<typeof buildStackedOption>[0]> = {}) =>
    buildStackedOption({
      rows: ROWS,
      series: SERIES,
      sort: 'value',
      scale: 'linear',
      mode: 'absolute',
      ...overrides,
    });

  it('emits one stacked series per segment kind', () => {
    const option = build() as { series: { name: string; stack: string }[] };
    expect(option.series.map((s) => s.name)).toEqual([
      'No findings',
      'Mandatory attribute empty',
    ]);
    expect(option.series.every((s) => s.stack === 'total')).toBe(true);
  });

  it('normalises each row against its own total in percentage mode', () => {
    // Rows of wildly different sizes are only comparable this way: Aircraft is half bad on ten
    // items, Segment is a tenth bad on a hundred.
    const option = build({ mode: 'percentage' }) as { series: { data: number[] }[] };
    expect(option.series[1].data).toEqual([50, 10]);
  });

  it('caps the axis at 100 in percentage mode', () => {
    const option = build({ mode: 'percentage' }) as { xAxis: { max?: number } };
    expect(option.xAxis.max).toBe(100);
  });

  // A logarithmic axis makes the sum of two segments not the length of both, so the picture would
  // be quietly false rather than merely hard to read.
  it('never draws a stack on a log axis, whatever the scale asks for', () => {
    const option = build({ scale: 'log' }) as { xAxis: { type: string } };
    expect(option.xAxis.type).toBe('value');
  });

  // Nothing in echarts reserves room for a legend, so the plot area has to give it up explicitly
  // or the legend lands on top of the value axis's own tick labels.
  it('keeps the legend out of the plot area it sits under', () => {
    const option = build() as { grid: { bottom: number }; legend: { bottom: number } };
    expect(option.legend.bottom).toBe(0);
    expect(option.grid.bottom).toBe(AXIS_STRIP);
  });

  // A wrapping legend grows upward into a strip of fixed height, which puts its second line back
  // over the axis. Four segment names on a narrow sheet is not a hypothetical: that is this chart.
  it('scrolls the legend rather than letting it wrap', () => {
    const option = build() as { legend: { type: string } };
    expect(option.legend.type).toBe('scroll');
  });
});

describe('click mapping', () => {
  // echarts reports a click as a dataIndex into the series it drew, and both builders feed their
  // data reversed. These helpers are the only place that reversal is expressed for lookup, so a
  // click can never resolve to the wrong row.
  it('resolves a bar dataIndex to the key of the bar actually drawn there', () => {
    const option = buildBarOption({
      data: BARS,
      sort: 'value',
      scale: 'linear',
      mode: 'absolute',
      total: 100,
      token: 'sec-blue-mid',
      valueName: 'Violations',
    }) as { yAxis: { data: string[] } };

    const keys = barKeysInRenderOrder(BARS, 'value');
    expect(keys).toEqual(['rationale', 'verif', 'prio']);
    // The key at index i belongs to the label the axis drew at index i.
    expect(option.yAxis.data[0]).toBe('Rationale');
    expect(keys[0]).toBe('rationale');
  });

  it('resolves a stacked dataIndex to the row actually drawn there', () => {
    const option = buildStackedOption({
      rows: ROWS,
      series: SERIES,
      sort: 'value',
      scale: 'linear',
      mode: 'absolute',
    }) as { yAxis: { data: string[] } };

    const keys = rowKeysInRenderOrder(ROWS, SERIES, 'value');
    expect(keys).toEqual(['b', 'a']);
    expect(option.yAxis.data[0]).toBe('Aircraft');
    expect(keys[0]).toBe('b');
  });
});
