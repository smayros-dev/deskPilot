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
});
