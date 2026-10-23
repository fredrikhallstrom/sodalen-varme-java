"""Estimators: filters and least squares, with no heating in them.

Split out of model.py because none of this knows what a radiator is. An
exponentially weighted mean, a line fit, and the matrix form of the same
thing would be the same code in a battery model or a water tank; keeping
them here says so, and means thermal.py and forecast.py can depend on the
arithmetic without dragging in a Tuning, a burner and a sensor role.

The one thing that is not generic is the decay: every one of these folds dt
into its coefficients rather than assuming a fixed step, because Home
Assistant is not a real-time system and a cycle that arrives late has to
filter by the time that actually passed.
"""

from __future__ import annotations

import math

from dataclasses import dataclass, field

from .parsing import as_float, as_mapping, as_optional_float


@dataclass
class Ewma:
    """First-order filter that copes with an irregular cycle time.

    Home Assistant is not a real-time system: a cycle can be late, and after
    a restart the gap is however long the restart took. Folding dt into the
    coefficient means a late cycle filters by the time that actually passed
    instead of quietly shortening the filter.
    """

    seconds: float
    value: float | None = None

    def update(self, sample: float | None, dt: float) -> float | None:
        if sample is None:
            return self.value
        if self.value is None or self.seconds <= 0.0 or dt <= 0.0:
            self.value = float(sample)
            return self.value
        # 1 - exp(-dt/tau) without importing math for one call.
        ratio = dt / self.seconds
        alpha = 1.0 - (2.718281828459045 ** -ratio) if ratio < 30.0 else 1.0
        self.value += alpha * (float(sample) - self.value)
        return self.value

    def as_dict(self) -> dict:
        return {"seconds": self.seconds, "value": self.value}

    @classmethod
    def from_dict(cls, data: dict | None, seconds: float) -> Ewma:
        data = as_mapping(data)
        return cls(seconds=seconds, value=as_optional_float(data.get("value")))


@dataclass
class DutyWatch:
    """What share of the time a condition held, over a fading window.

    Only time when the condition could be judged at all counts - a reading
    of "held" or "did not hold" - and the rest is simply not evidence
    either way. `hours` says how much evidence there is, so a share of 100 %
    from ten minutes can be told apart from one from three days.
    """

    seconds: float
    share: float | None = None
    weight: float = 0.0

    def update(self, held: bool | None, dt: float) -> None:
        if dt <= 0.0:
            return
        ratio = dt / self.seconds if self.seconds > 0.0 else 30.0
        self.weight *= 2.718281828459045 ** -ratio if ratio < 30.0 else 0.0
        if held is None:
            return
        total = self.weight + dt
        sample = 1.0 if held else 0.0
        self.share = sample if self.share is None else (
            self.share + (sample - self.share) * dt / total
        )
        self.weight = total

    @property
    def hours(self) -> float:
        return self.weight / 3600.0

    def as_dict(self) -> dict:
        return {"seconds": self.seconds, "share": self.share, "weight": self.weight}

    @classmethod
    def from_dict(cls, data: dict | None, seconds: float) -> DutyWatch:
        data = as_mapping(data)
        return cls(
            seconds=seconds,
            share=as_optional_float(data.get("share")),
            weight=as_float(data.get("weight"), 0.0),
        )


@dataclass
class Regression:
    """Exponentially weighted least squares fit of y = a + b*x.

    Two jobs in this controller, which is why it is its own type: learning
    the heating curve (x = normalised outdoor demand, y = indoor error) and
    measuring the valve's static gain (x = valve position, y = flow
    temperature). Old samples fade rather than being dropped, so there is no
    buffer to size and a house that changes - windows replaced, a room shut
    off - is followed rather than averaged with its own history.
    """

    horizon: float
    weight: float = 0.0
    sum_x: float = 0.0
    sum_y: float = 0.0
    sum_xx: float = 0.0
    sum_xy: float = 0.0

    def add(self, x: float, y: float, dt: float) -> None:
        decay = 1.0
        if self.horizon > 0.0 and dt > 0.0:
            ratio = dt / self.horizon
            decay = (2.718281828459045 ** -ratio) if ratio < 30.0 else 0.0
        self.weight = self.weight * decay + 1.0
        self.sum_x = self.sum_x * decay + x
        self.sum_y = self.sum_y * decay + y
        self.sum_xx = self.sum_xx * decay + x * x
        self.sum_xy = self.sum_xy * decay + x * y

    def fade(self, dt: float) -> None:
        """Let time pass without a sample. add() fades what was seen only
        when something new comes, so a fit that learns now and then keeps
        weeks-old samples at full weight; a caller that wants the fit to
        mean "lately" by the clock fades it every cycle and adds with dt 0."""
        if self.horizon <= 0.0 or dt <= 0.0:
            return
        ratio = dt / self.horizon
        decay = (2.718281828459045 ** -ratio) if ratio < 30.0 else 0.0
        self.weight *= decay
        self.sum_x *= decay
        self.sum_y *= decay
        self.sum_xx *= decay
        self.sum_xy *= decay

    @property
    def mean_x(self) -> float:
        return self.sum_x / self.weight if self.weight > 0.0 else 0.0

    @property
    def mean_y(self) -> float:
        return self.sum_y / self.weight if self.weight > 0.0 else 0.0

    @property
    def variance_x(self) -> float:
        if self.weight <= 0.0:
            return 0.0
        return max(0.0, self.sum_xx / self.weight - self.mean_x * self.mean_x)

    def fit(self, min_variance: float) -> tuple[float, float, bool]:
        """Returns (intercept, slope, slope_is_identifiable).

        With every sample taken at nearly the same x - a week of the same
        weather, or a valve that has not moved - the slope is not in the
        data at all. Saying so lets the caller move the offset only, instead
        of dividing by a variance that is numerically zero.
        """
        if self.weight < 2.0:
            return 0.0, 0.0, False

        variance = self.variance_x
        if variance < min_variance:
            return self.mean_y, 0.0, False

        covariance = self.sum_xy / self.weight - self.mean_x * self.mean_y
        slope = covariance / variance
        return self.mean_y - slope * self.mean_x, slope, True

    def as_dict(self) -> dict:
        return {
            "horizon": self.horizon,
            "weight": self.weight,
            "sum_x": self.sum_x,
            "sum_y": self.sum_y,
            "sum_xx": self.sum_xx,
            "sum_xy": self.sum_xy,
        }

    @classmethod
    def from_dict(cls, data: dict | None, horizon: float) -> Regression:
        data = as_mapping(data)
        return cls(
            horizon=horizon,
            weight=as_float(data.get("weight"), 0.0),
            sum_x=as_float(data.get("sum_x"), 0.0),
            sum_y=as_float(data.get("sum_y"), 0.0),
            sum_xx=as_float(data.get("sum_xx"), 0.0),
            sum_xy=as_float(data.get("sum_xy"), 0.0),
        )


def solve_symmetric(matrix: list[list[float]], rhs: list[float]) -> list[float] | None:
    """Solve a small symmetric positive-definite system by Gauss-Jordan.

    Written out rather than imported because this integration has no
    dependencies and the tests run without Home Assistant - and because a
    six-by-six solve is thirty lines, which is cheaper than a dependency.

    Returns None when the system is singular, which here means the data has
    not yet distinguished the parameters from each other.
    """
    size = len(rhs)
    work = [list(row) + [rhs[i]] for i, row in enumerate(matrix)]

    for column in range(size):
        pivot_row = max(range(column, size), key=lambda r: abs(work[r][column]))
        if abs(work[pivot_row][column]) < 1e-12:
            return None
        work[column], work[pivot_row] = work[pivot_row], work[column]

        pivot = work[column][column]
        for index in range(column, size + 1):
            work[column][index] /= pivot

        for row in range(size):
            if row == column:
                continue
            factor = work[row][column]
            if factor == 0.0:
                continue
            for index in range(column, size + 1):
                work[row][index] -= factor * work[column][index]

    return [work[row][size] for row in range(size)]


@dataclass
class MultiRegression:
    """Exponentially weighted least squares in several variables, with an
    honest opinion about how far it is guessing.

    The single-variable Regression above learns the heating curve. This one
    learns the house: how fast it cools, how much the wind adds to that from
    each direction, and how much the sun puts back. Those are six parameters
    against one measurement, so it needs the matrix form.

    The point of keeping the whole matrix rather than just the fit is
    variance(x). A fit that has only ever seen mild, still weather will
    happily produce a number for a gale at minus twenty; what it cannot do
    is produce a small variance for it. That is what makes confidence local
    rather than global - the model does not switch between learning and
    knowing, it simply becomes less sure the further it is asked to reach,
    and a decision that needs certainty stops being taken.
    """

    size: int
    horizon: float
    ridge: float = 1e-6
    weight: float = 0.0
    xx: list[list[float]] = field(default_factory=list)
    xy: list[float] = field(default_factory=list)
    # Sum of squared residuals, same decay: how wrong it has been lately.
    residual: float = 0.0
    # The same, measured against the fit from the samples alone - without
    # the priors and floors below. sigma uses the larger of the two, so
    # knowing the physics can make the estimate better but never makes the
    # fit surer of itself than its data allows.
    residual_data: float = 0.0
    # Sum of y squared, same decay, for the residual against the fit itself
    # (in_sample_sigma). None when stored before it was kept.
    yy: float | None = 0.0
    # What physics already says about some coefficients, as index ->
    # (expected value, strength). The strength is in the units of the
    # matrix: samples times the square of how much that regressor is
    # expected to vary, i.e. as much information as that many samples of
    # typical spread would carry. Pulls the estimate towards the value where
    # the data cannot tell - a direction the samples never varied along -
    # and fades as the data grows. Configuration, not state: not persisted,
    # set by the owner of the fit.
    priors: dict = field(default_factory=dict)
    # Hard lower bounds, index -> floor, for coefficients whose sign is
    # known. See coefficients().
    floors: dict = field(default_factory=dict)
    # The solved coefficients, kept until a sample or the configuration
    # changes them. The house model asks for predictions dozens of times a
    # cycle, and every one used to repeat the solve.
    _beta: list | None = field(default=None, repr=False, compare=False)
    _beta_ready: bool = field(default=False, repr=False, compare=False)

    def configure(self, priors: dict, floors: dict) -> None:
        self.priors = dict(priors)
        self.floors = dict(floors)
        self._beta_ready = False

    def __post_init__(self) -> None:
        if not self.xx:
            self.xx = [[0.0] * self.size for _ in range(self.size)]
        if not self.xy:
            self.xy = [0.0] * self.size

    def _decay(self, dt: float) -> float:
        if self.horizon <= 0.0 or dt <= 0.0:
            return 1.0
        ratio = dt / self.horizon
        return (2.718281828459045 ** -ratio) if ratio < 30.0 else 0.0

    def add(self, x: list[float], y: float, dt: float) -> None:
        # A sample of the wrong shape is a programming error, but raising it
        # here would stop the control cycle, and a stopped cycle stops the
        # heartbeat the Shelly is watching. Dropping it costs one sample.
        if len(x) != self.size:
            return
        decay = self._decay(dt)
        # Residual measured against the fit as it was *before* this sample,
        # which is the only version that had not already seen it.
        predicted = self.predict(x)
        if predicted is not None:
            self.residual = self.residual * decay + (y - predicted) ** 2
        else:
            self.residual *= decay
        # Without priors or floors the data-only fit is the fit, and its
        # residual the one just taken.
        from_data = self._data_coefficients() if (self.priors or self.floors) else None
        if from_data is None and not (self.priors or self.floors):
            self.residual_data = self.residual
        elif from_data is not None:
            guess = sum(b * xi for b, xi in zip(from_data, x))
            self.residual_data = self.residual_data * decay + (y - guess) ** 2
        else:
            self.residual_data *= decay

        self._beta_ready = False
        if self.yy is not None:
            self.yy = self.yy * decay + y * y
        self.weight = self.weight * decay + 1.0
        for row in range(self.size):
            self.xy[row] = self.xy[row] * decay + x[row] * y
            for col in range(self.size):
                self.xx[row][col] = self.xx[row][col] * decay + x[row] * x[col]

    def rescale(self, index: int, factor: float) -> None:
        """The same samples with regressor `index` counted in a unit
        `factor` times larger, x' = factor * x: its coefficient comes out
        `factor` times smaller and every prediction made with x' is the one
        made with x before. For a regressor whose unit changes under a
        model that has already learnt it - the sun's, from the index to the
        light on the walls (2026-10-18)."""
        if not 0 <= index < self.size:
            raise ValueError(f"no regressor {index} in {self.size}")
        if not (math.isfinite(factor) and factor > 0.0):
            raise ValueError(f"a unit is a positive number, not {factor}")
        for other in range(self.size):
            self.xx[index][other] *= factor
            self.xx[other][index] *= factor
        self.xy[index] *= factor
        self._beta_ready = False

    def _regularised(self) -> list[list[float]]:
        return [
            [
                value + (self.ridge * max(1.0, self.weight) if row == col else 0.0)
                for col, value in enumerate(self.xx[row])
            ]
            for row in range(self.size)
        ]

    def _data_coefficients(self) -> list[float] | None:
        """The least-squares fit of the samples alone."""
        if self.weight < self.size + 1:
            return None
        return solve_symmetric(self._regularised(), list(self.xy))

    def coefficients(self) -> list[float] | None:
        """The fit, with the priors and floors applied, cached until the
        next sample.

        The floors are solved exactly: every combination of which bounded
        coefficients sit on their floor is tried, those pinned and the rest
        solved, and the best combination that respects every floor wins.
        With two bounds that is four small solves. The first version pinned
        whatever fell below and never let go, which can pin one too many -
        with the wall loss and the radiators both below their floors it
        pinned both, where pinning the radiators alone is the better fit,
        and the model predicted a house cooling four times too slowly.
        """
        if self._beta_ready:
            return self._beta
        self._beta = self._solve()
        self._beta_ready = True
        return self._beta

    def _solve(self) -> list[float] | None:
        if self.weight < self.size + 1:
            return None
        matrix = self._regularised()
        rhs = list(self.xy)
        for index, (expected, strength) in self.priors.items():
            # A fixed amount of information rather than one scaled to the
            # data: scaled to the column's raw size it would weigh as much
            # as the data forever when a regressor sits far from zero but
            # hardly varies - the house's inside-outside difference is ten
            # degrees give or take two.
            matrix[index][index] += strength
            rhs[index] += strength * expected

        # Usually nothing is below its floor, and then the plain solve is
        # the answer - the enumeration below only runs when a floor binds.
        plain = solve_symmetric(matrix, rhs)
        if plain is not None and all(
            plain[index] >= floor - 1e-12 for index, floor in self.floors.items()
        ):
            return plain

        bounded = sorted(self.floors)
        best, best_cost = None, None
        for mask in range(1 << len(bounded)):
            pinned = {
                index: self.floors[index]
                for bit, index in enumerate(bounded) if mask >> bit & 1
            }
            beta = self._solve_pinned(matrix, rhs, pinned)
            if beta is None:
                continue
            if any(beta[i] < self.floors[i] - 1e-12 for i in bounded if i not in pinned):
                continue
            # The quadratic being minimised, up to a constant.
            cost = sum(
                beta[r] * matrix[r][c] * beta[c]
                for r in range(self.size) for c in range(self.size)
            ) - 2.0 * sum(b * v for b, v in zip(beta, rhs))
            if best_cost is None or cost < best_cost:
                best, best_cost = beta, cost
        return best

    def _solve_pinned(self, matrix, rhs, pinned):
        free = [i for i in range(self.size) if i not in pinned]
        reduced = [[matrix[r][c] for c in free] for r in free]
        reduced_rhs = [
            rhs[r] - sum(matrix[r][p] * value for p, value in pinned.items())
            for r in free
        ]
        solved = solve_symmetric(reduced, reduced_rhs) if free else []
        if solved is None:
            return None
        beta = [0.0] * self.size
        for index, value in zip(free, solved):
            beta[index] = value
        for index, value in pinned.items():
            beta[index] = value
        return beta

    def predict(self, x: list[float]) -> float | None:
        if len(x) != self.size:
            return None
        beta = self.coefficients()
        if beta is None:
            return None
        return sum(b * xi for b, xi in zip(beta, x))

    @property
    def sigma(self) -> float:
        """Typical size of the residual, in the units of y."""
        degrees = self.weight - self.size
        if degrees <= 0.0:
            return float("inf")
        worst = max(self.residual, self.residual_data)
        return (max(0.0, worst) / degrees) ** 0.5

    def in_sample_sigma(self) -> float:
        """Typical size of the residual against the fit as it stands - priors
        and floors included - over the degrees of freedom: the textbook
        estimate, there from the first answer. sigma counts the errors of
        guesses made before each sample, and before the fit can answer it
        makes none; a fit of one sample a night read nothing at all when it
        began to answer. Stored before yy was kept, it is sigma."""
        if self.yy is None:
            return self.sigma
        degrees = self.weight - self.size
        beta = self.coefficients()
        if degrees <= 0.0 or beta is None:
            return float("inf")
        squares = (
            self.yy
            - 2.0 * sum(b * v for b, v in zip(beta, self.xy))
            + sum(beta[r] * self.xx[r][c] * beta[c]
                  for r in range(self.size) for c in range(self.size))
        )
        return (max(0.0, squares) / degrees) ** 0.5

    def reach(self, x: list[float]) -> float | None:
        """How far beyond its own data the fit is being asked to go.

        sqrt(1 + leverage) - 1, which is variance(x) divided by sigma and
        shifted to start at zero: nothing at the heart of the data, growing
        without bound outside it.

        Dimensionless on purpose. variance(x) carries sigma, so its floor is
        whatever the sensors happen to be worth and a threshold against it
        has to be retuned per building - and set below that floor, as ours
        was, it never fires at all. Dividing the noise back out means a house
        with coarse sensors and one with fine ones agree about what
        "familiar" means, and only the extrapolation is left.
        """
        if len(x) != self.size or self.weight < self.size + 1:
            return None
        solved = solve_symmetric(self._regularised(), list(x))
        if solved is None:
            return None
        leverage = max(0.0, sum(xi * si for xi, si in zip(x, solved)))
        return (1.0 + leverage) ** 0.5 - 1.0

    def variance(self, x: list[float]) -> float | None:
        """How uncertain a prediction at x is, in y squared.

        sigma^2 * x' (X'X)^-1 x - the textbook leverage. Small where the fit
        has data, large where it is extrapolating, which is the whole
        mechanism behind confidence being local.
        """
        if len(x) != self.size or self.weight < self.size + 1:
            return None
        solved = solve_symmetric(self._regularised(), list(x))
        if solved is None:
            return None
        leverage = max(0.0, sum(xi * si for xi, si in zip(x, solved)))
        sigma = self.sigma
        if sigma == float("inf"):
            return None
        return sigma * sigma * (1.0 + leverage)

    def uncertainty(self, x: list[float]) -> float | None:
        """The same thing as a standard deviation in y."""
        variance = self.variance(x)
        return None if variance is None else variance ** 0.5

    def as_dict(self) -> dict:
        return {
            "size": self.size,
            "horizon": self.horizon,
            "weight": self.weight,
            "xx": [list(row) for row in self.xx],
            "xy": list(self.xy),
            "residual": self.residual,
            "residual_data": self.residual_data,
            "yy": self.yy,
        }

    @classmethod
    def from_dict(cls, data: dict | None, size: int, horizon: float) -> MultiRegression:
        data = as_mapping(data)
        try:
            stored_size = int(data.get("size") or 0)
        except (TypeError, ValueError):
            stored_size = 0
        xx = data.get("xx")
        xy = data.get("xy")
        # A change of size means the model was rebuilt under the operator's
        # feet - a regressor added or dropped. Starting over is the only
        # honest answer; the old numbers describe a different question.
        if stored_size != size or not isinstance(xx, list) or not isinstance(xy, list):
            return cls(size=size, horizon=horizon)
        try:
            xx = [[float(v) for v in row] for row in xx]
            xy = [float(v) for v in xy]
            if len(xy) != size or any(len(row) != size for row in xx) or len(xx) != size:
                raise ValueError
        except (TypeError, ValueError):
            return cls(size=size, horizon=horizon)
        return cls(
            size=size,
            horizon=horizon,
            weight=as_float(data.get("weight"), 0.0),
            xx=xx,
            xy=xy,
            residual=as_float(data.get("residual"), 0.0),
            # Stored before it existed: the model's own residual is the only
            # history there is, and the larger one wins anyway.
            residual_data=as_float(data.get("residual_data"), as_float(data.get("residual"), 0.0)),
            yy=as_optional_float(data.get("yy")),
        )
