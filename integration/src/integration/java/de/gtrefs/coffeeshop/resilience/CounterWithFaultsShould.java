package de.gtrefs.coffeeshop.resilience;

import de.gtrefs.coffeeshop.*;
import io.restassured.specification.*;

import net.jqwik.api.*;
import net.jqwik.api.stateful.*;
import net.jqwik.api.statistics.*;

// Please note, that shrinking is turned off for all the following properties.
// As we discussed, in the last part of the workshop, shrinking helps us to
// find a minimal or significant less complex example which falsifies our
// properties. However, in a stateful environment shrinking requires a reset
// of the state. Otherwise, a non-deterministic outcome is very likely.
//
// Resetting our application is costly in terms of time. All containers are
// stopped and started again. Sometimes there are easier ways to reset an
// application. For example, Spring Boot offers a way to restart an application
// and we could cleanse our database. Though, spending this effort is not worth
// it most of the times, as there are other ways to determine the root cause.
// For example, logs and metrics.
//
// That is: We trade in determinism for speed.
public class CounterWithFaultsShould extends CoffeeShopWithFaults {

	private CoffeeShopModel model = new CoffeeShopModel("melange|black|espresso|ristretto|cappuccino");

	// Let's revisit our test from before. We don't want to run people into
	// significant debt. This time the tests itself looks much smaller. The
	// actual check moved to the ModelResponse class.
	@Property(shrinking = ShrinkingMode.OFF)
	public void not_run_people_into_debt(@ForAll("orders_for_the_same_credit_card") ActionSequence<RequestSpecification> orders){
		orders.run(counter);
	}

	@Provide
	private ActionSequenceArbitrary<RequestSpecification> orders_for_the_same_credit_card(){
		var flavors = Arbitraries.of("Black", "Melange", "Espresso", "Ristretto", "Cappuccino");
		var creditCardNumber = Arbitraries.just("98236587");
		Arbitrary<Action<RequestSpecification>> orders = Combinators.combine(flavors, creditCardNumber)
																 .as(Order::new)
																 .map(order -> new OrderCoffee(model, order, "Existing"));
		return Arbitraries.sequences(orders);
	}

	// A model allows us to model the desired behavior that we want to check.
	// It is the source of truth for our application. Usually, if a tests
	// fails, it is the application which it is our application.
	@Property(shrinking = ShrinkingMode.OFF, tries = 100)
	public void return_successful_orders(@ForAll("order_and_get_orders") ActionSequence<RequestSpecification> actions){
		actions.run(counter);
	}

	@Provide
	private ActionSequenceArbitrary<RequestSpecification> order_and_get_orders(){
		return Arbitraries.sequences(Arbitraries.frequencyOf(Tuple.of(10, orderExistingFlavor()), Tuple.of(1, checkState())));
	}
	// Exercise 2: In the test above we checked that we can get the state
	// of successful orders. But how should we handle orders which are rejected?
	// Maybe our Barista does not know how to make a coffee with a specific
	// flavor? Maybe payment fails? Should the state of the order be saved
	// in such a case? If we do, how do we need to adapt our model and code in
	// order to reflect this?
	// Hint: The order service does not return our order when it fails. But,
	// our model depends on this id. Maybe you can adapt the service? If you
	// decide to *not* store an order when it was unsuccessful, how do you do
	// that?

	@Property(shrinking = ShrinkingMode.OFF, tries = 100)
	public void return_unsuccessful_orders(@ForAll("order_existing_and_not_existing_flavors") ActionSequence<RequestSpecification> actions) {
		actions.run(counter);
	}

	@Provide
	private ActionSequenceArbitrary<RequestSpecification> order_existing_and_not_existing_flavors(){
		return Arbitraries.sequences(Arbitraries.frequencyOf(Tuple.of(50, orderExistingFlavor()),
															 Tuple.of(10, orderRandomFlavor()),
															 Tuple.of(5, Arbitraries.create(() -> new EnableDatabase(model))),
															 Tuple.of(5, Arbitraries.create(() -> new DisableDatabase(model))),
															 Tuple.of(10, checkState())));
	}

	private Arbitrary<Action<RequestSpecification>> checkState() {
		return Arbitraries.longs().between(1, 5000).map(id -> new CheckStatus(model, id));
	}

	private Arbitrary<Action<RequestSpecification>> orderRandomFlavor() {
		var flavors = Arbitraries.strings().ascii().ofMinLength(3).ofMinLength(15);
		var creditCardNumbers = Arbitraries.strings().numeric().ofMinLength(13).ofMaxLength(16);
		return Combinators.combine(flavors, creditCardNumbers).as(Order::new).map(order -> new OrderCoffee(model, order, "Random Flavor"));
	}

	private Arbitrary<Action<RequestSpecification>> orderExistingFlavor() {
		var flavors = Arbitraries.of("Black", "Melange", "Espresso", "Ristretto", "Cappuccino");
		var creditCardNumbers = Arbitraries.strings().numeric().ofMinLength(13).ofMaxLength(16);
		return Combinators.combine(flavors, creditCardNumbers).as(Order::new).map(order -> new OrderCoffee(model, order, "Existing Flavor"));
	}

	public class OrderCoffee implements Action<RequestSpecification> {

		private final CoffeeShopModel model;
		private final Order order;
		private final String statisticsMessage;

		public OrderCoffee(CoffeeShopModel model, Order order, String statisticsMessage) {
			this.model = model;
			this.order = order;
			this.statisticsMessage = statisticsMessage;
		}

		@Override
		public boolean precondition(RequestSpecification state) {
			Statistics.label("Action").collect("Order Coffee: "+statisticsMessage);
			return true;
		}

		@Override
		public RequestSpecification run(RequestSpecification state) {
			var response = state.body(order).post("/order");
			model.order(order).checkPostCondition(response);
			return state;
		}

		@Override
		public String toString() {
			return "OrderCoffee{" +
					"order=" + order +
					'}';
		}
	}

	public class CheckStatus implements Action<RequestSpecification> {

		private final CoffeeShopModel model;
		private Long orderId;

		public CheckStatus(CoffeeShopModel model, Long orderId) {
			this.model = model;
			this.orderId = orderId;
		}

		@Override
		public boolean precondition(RequestSpecification state) {
			Statistics.label("Action").collect("Check order state");
			return true;
		}

		@Override
		public RequestSpecification run(RequestSpecification state) {
			var response = state.body(orderId).get("/order/"+orderId);
			model.checkStatus(orderId).checkPostCondition(response);
			return state;
		}

		@Override
		public String toString() {
			return "CheckStatus{" +
					"orderId=" + orderId +
					'}';
		}
	}

	public class DisableDatabase implements Action<RequestSpecification> {

		private final CoffeeShopModel model;

		public DisableDatabase(CoffeeShopModel model) {
			this.model = model;
		}

		@Override
		public boolean precondition(RequestSpecification state) {
			boolean databaseEnabled = model.databaseEnabled();
			if (databaseEnabled) {
				Statistics.label("Fault Injection").collect("Disable Database");
			}
			return databaseEnabled;
		}

		@Override
		public RequestSpecification run(RequestSpecification state) {
			postgresProxy.disable();
			model.disableDatabase();
			return state;
		}

		@Override
		public String toString() {
			return "DisableDatabase{}";
		}
	}

	public class EnableDatabase implements Action<RequestSpecification> {

		private final CoffeeShopModel model;

		public EnableDatabase(CoffeeShopModel model) {
			this.model = model;
		}

		@Override
		public boolean precondition(RequestSpecification state) {
			boolean databaseDisabled = model.isDatabaseDisabled();
			if (databaseDisabled) {
				Statistics.label("Fault Injection").collect("Enable Database");
			}
			return databaseDisabled;
		}

		@Override
		public RequestSpecification run(RequestSpecification state) {
			postgresProxy.enable();
			model.enableDatabase();
			return state;
		}

		@Override
		public String toString() {
			return "EnableDatabase{}";
		}
	}

}
