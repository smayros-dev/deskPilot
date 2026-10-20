import { DurationPipe } from './duration-pipe';

describe('DurationPipe', () => {
  const pipe = new DurationPipe();

  it.each([
    [null, '–'],
    [850, '850 ms'],
    [4200, '4.2 s'],
    [45_000, '45 s'],
    [60_000, '1 min'],
    [95_000, '1 min 35 s'],
  ])('formats %s as %s', (input, expected) => {
    expect(pipe.transform(input)).toBe(expected);
  });

  it('uses the units and decimal separator of the UI language', () => {
    expect(pipe.transform(4200, 'fr')).toBe('4,2 s');
    expect(pipe.transform(95_000, 'fr')).toBe('1 min 35 s');
    expect(pipe.transform(95_000, 'ar')).toBe('1 د 35 ث');
  });
});
