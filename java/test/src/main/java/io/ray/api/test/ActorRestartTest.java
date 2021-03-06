package io.ray.api.test;

import io.ray.api.ActorHandle;
import io.ray.api.Checkpointable;
import io.ray.api.Ray;
import io.ray.api.TestUtils;
import io.ray.api.exception.RayActorException;
import io.ray.api.id.ActorId;
import io.ray.api.id.UniqueId;
import io.ray.api.options.ActorCreationOptions;
import io.ray.runtime.util.SystemUtil;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class ActorRestartTest extends BaseTest {

  public static class Counter {

    protected int value = 0;

    private boolean wasCurrentActorRestarted = false;

    public Counter() {
      wasCurrentActorRestarted = Ray.getRuntimeContext().wasCurrentActorRestarted();
    }

    public boolean wasCurrentActorRestarted() {
      return wasCurrentActorRestarted;
    }

    public int increase() {
      value += 1;
      return value;
    }

    public int getPid() {
      return SystemUtil.pid();
    }
  }

  public void testActorRestart() throws InterruptedException, IOException {
    TestUtils.skipTestUnderSingleProcess();
    ActorCreationOptions options =
        new ActorCreationOptions.Builder().setMaxRestarts(1).createActorCreationOptions();
    ActorHandle<Counter> actor = Ray.createActor(Counter::new, options);
    // Call increase 3 times.
    for (int i = 0; i < 3; i++) {
      actor.call(Counter::increase).get();
    }

    Assert.assertFalse(actor.call(Counter::wasCurrentActorRestarted).get());

    // Kill the actor process.
    int pid = actor.call(Counter::getPid).get();
    Runtime.getRuntime().exec("kill -9 " + pid);
    // Wait for the actor to be killed.
    TimeUnit.SECONDS.sleep(1);

    int value = actor.call(Counter::increase).get();
    Assert.assertEquals(value, 1);

    Assert.assertTrue(actor.call(Counter::wasCurrentActorRestarted).get());

    // Kill the actor process again.
    pid = actor.call(Counter::getPid).get();
    Runtime.getRuntime().exec("kill -9 " + pid);
    TimeUnit.SECONDS.sleep(1);

    // Try calling increase on this actor again and this should fail.
    try {
      actor.call(Counter::increase).get();
      Assert.fail("The above task didn't fail.");
    } catch (RayActorException e) {
      // We should receive a RayActorException because the actor is dead.
    }
  }

  public static class CheckpointableCounter extends Counter implements Checkpointable {

    private boolean resumedFromCheckpoint = false;
    private boolean increaseCalled = false;

    @Override
    public int increase() {
      increaseCalled = true;
      return super.increase();
    }

    public boolean wasResumedFromCheckpoint() {
      return resumedFromCheckpoint;
    }

    @Override
    public boolean shouldCheckpoint(CheckpointContext checkpointContext) {
      // Checkpoint the actor when value is increased to 3.
      boolean shouldCheckpoint = increaseCalled && value == 3;
      increaseCalled = false;
      return shouldCheckpoint;
    }

    @Override
    public void saveCheckpoint(ActorId actorId, UniqueId checkpointId) {
      // In practice, user should save the checkpoint id and data to a persistent store.
      // But for simplicity, we don't do that in this unit test.
    }

    @Override
    public UniqueId loadCheckpoint(ActorId actorId, List<Checkpoint> availableCheckpoints) {
      // Restore previous value and return checkpoint id.
      this.value = 3;
      this.resumedFromCheckpoint = true;
      return availableCheckpoints.get(availableCheckpoints.size() - 1).checkpointId;
    }

    @Override
    public void checkpointExpired(ActorId actorId, UniqueId checkpointId) {
    }
  }

  public void testActorCheckpointing() throws IOException, InterruptedException {
    TestUtils.skipTestUnderSingleProcess();
    ActorCreationOptions options =
        new ActorCreationOptions.Builder().setMaxRestarts(1).createActorCreationOptions();
    ActorHandle<CheckpointableCounter> actor = Ray.createActor(CheckpointableCounter::new, options);
    // Call increase 3 times.
    for (int i = 0; i < 3; i++) {
      actor.call(CheckpointableCounter::increase).get();
    }
    // Assert that the actor wasn't resumed from a checkpoint.
    Assert.assertFalse(actor.call(CheckpointableCounter::wasResumedFromCheckpoint).get());
    int pid = actor.call(CheckpointableCounter::getPid).get();
    Runtime.getRuntime().exec("kill -9 " + pid);
    // Wait for the actor to be killed.
    TimeUnit.SECONDS.sleep(1);

    // Try calling increase on this actor again and check the value is now 4.
    int value = actor.call(CheckpointableCounter::increase).get();
    Assert.assertEquals(value, 4);
    // Assert that the actor was resumed from a checkpoint.
    Assert.assertTrue(actor.call(CheckpointableCounter::wasResumedFromCheckpoint).get());
  }
}

