package org.apache.mahout.df.mapreduce.partial;

import static org.apache.mahout.df.data.Utils.double2String;
import static org.apache.mahout.df.data.Utils.randomDescriptor;
import static org.apache.mahout.df.data.Utils.randomDoubles;

import java.util.Random;

import junit.framework.TestCase;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.mahout.df.builder.TreeBuilder;
import org.apache.mahout.df.data.Data;
import org.apache.mahout.df.data.DataLoader;
import org.apache.mahout.df.data.Dataset;
import org.apache.mahout.df.data.Utils;
import org.apache.mahout.df.mapreduce.partial.TreeID;
import org.apache.mahout.df.node.Leaf;
import org.apache.mahout.df.node.Node;

public class Step1MapperTest extends TestCase {

  /**
   * Make sure that the data used to build the trees is from the mapper's
   * partition
   * 
   */
  private static class MockTreeBuilder extends TreeBuilder {

    protected Data expected;

    public void setExpected(Data data) {
      expected = data;
    }

    @Override
    public Node build(Random rng, Data data) {
      for (int index = 0; index < data.size(); index++) {
        assertTrue(expected.contains(data.get(index)));
      }

      return new Leaf(-1);
    }
  }

  /**
   * Special Step1Mapper that can be configured without using a Configuration
   * 
   */
  protected static class MockStep1Mapper extends Step1Mapper {
    public MockStep1Mapper(TreeBuilder treeBuilder, Dataset dataset, Long seed,
        int partition, int numMapTasks, int numTrees) {
      configure(false, true, treeBuilder, dataset);
      configure(seed, partition, numMapTasks, numTrees);
    }

    public int getFirstTreeId() {
      return firstTreeId;
    }

  }

  /** nb attributes per generated data instance */
  protected final int nbAttributes = 4;

  /** nb generated data instances */
  protected final int nbInstances = 100;

  /** nb trees to build */
  protected final int nbTrees = 10;

  /** nb mappers to use */
  protected final int nbMappers = 2;

  @SuppressWarnings("unchecked")
  public void testMapper() throws Exception {
    Long seed = null;
    Random rng = new Random();

    // prepare the data
    String descriptor = randomDescriptor(rng, nbAttributes);
    double[][] source = randomDoubles(rng, descriptor, nbInstances);
    String[] sData = double2String(source);
    Dataset dataset = DataLoader.generateDataset(descriptor, sData);
    String[][] splits = Utils.splitData(sData, nbMappers);

    MockTreeBuilder treeBuilder = new MockTreeBuilder();

    LongWritable key = new LongWritable();
    Text value = new Text();

    int treeIndex = 0;

    for (int partition = 0; partition < nbMappers; partition++) {
      String[] split = splits[partition];
      treeBuilder.setExpected(DataLoader.loadData(dataset, split));

      // expected number of trees that this mapper will build
      int mapNbTrees = Step1Mapper.nbTrees(nbMappers, nbTrees, partition);

      MockContext context = new MockContext(new Step1Mapper(),
          new Configuration(), new TaskAttemptID(), mapNbTrees);

      MockStep1Mapper mapper = new MockStep1Mapper(treeBuilder, dataset, seed,
          partition, nbMappers, nbTrees);

      // make sure the mapper computed firstTreeId correctly
      assertEquals(treeIndex, mapper.getFirstTreeId());

      for (int index = 0; index < split.length; index++) {
        key.set(index);
        value.set(split[index]);
        mapper.map(key, value, context);
      }

      mapper.cleanup(context);

      // make sure the mapper built all its trees
      assertEquals(mapNbTrees, context.nbOutputs());

      // check the returned keys
      for (TreeID k : context.keys) {
        assertEquals(partition, k.partition());
        assertEquals(treeIndex, k.treeId());

        treeIndex++;
      }
    }
  }
}