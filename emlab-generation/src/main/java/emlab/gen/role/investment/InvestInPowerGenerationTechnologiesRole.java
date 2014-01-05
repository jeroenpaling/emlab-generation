/*******************************************************************************
 * Copyright 2013 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package emlab.gen.role.investment;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.data.annotation.Transient;
import org.springframework.data.neo4j.annotation.NodeEntity;
import org.springframework.data.neo4j.aspects.core.NodeBacked;
import org.springframework.data.neo4j.support.Neo4jTemplate;
import org.springframework.transaction.annotation.Transactional;

import agentspring.role.Role;
import emlab.gen.domain.agent.BigBank;
import emlab.gen.domain.agent.EnergyProducer;
import emlab.gen.domain.agent.PowerPlantManufacturer;
import emlab.gen.domain.agent.StrategicReserveOperator;
import emlab.gen.domain.contract.CashFlow;
import emlab.gen.domain.contract.Loan;
import emlab.gen.domain.gis.Zone;
import emlab.gen.domain.market.ClearingPoint;
import emlab.gen.domain.market.electricity.ElectricitySpotMarket;
import emlab.gen.domain.market.electricity.Segment;
import emlab.gen.domain.market.electricity.SegmentLoad;
import emlab.gen.domain.policy.PowerGeneratingTechnologyTarget;
import emlab.gen.domain.sitelocation.LocalGovernment;
import emlab.gen.domain.sitelocation.Location;
import emlab.gen.domain.sitelocation.LocationLocalParties;
import emlab.gen.domain.technology.PowerGeneratingTechnology;
import emlab.gen.domain.technology.PowerGeneratingTechnologyNodeLimit;
import emlab.gen.domain.technology.PowerGridNode;
import emlab.gen.domain.technology.PowerPlant;
import emlab.gen.domain.technology.Substance;
import emlab.gen.domain.technology.SubstanceShareInFuelMix;
import emlab.gen.repository.Reps;
import emlab.gen.repository.StrategicReserveOperatorRepository;
import emlab.gen.util.GeometricTrendRegression;
import emlab.gen.util.MapValueComparator;
import emlab.gen.util.MapValueReverseComparator;

/**
 * {@link EnergyProducer}s decide to invest in new {@link PowerPlant}
 * 
 * @author <a href="mailto:E.J.L.Chappin@tudelft.nl">Emile Chappin</a> @author
 *         <a href="mailto:A.Chmieliauskas@tudelft.nl">Alfredas
 *         Chmieliauskas</a>
 * @author JCRichstein
 */
@Configurable
@NodeEntity
public class InvestInPowerGenerationTechnologiesRole<T extends EnergyProducer> extends GenericInvestmentRole<T>
        implements Role<T>, NodeBacked {

    @Transient
    @Autowired
    Reps reps;

    @Transient
    @Autowired
    Neo4jTemplate template;

    @Transient
    @Autowired
    StrategicReserveOperatorRepository strategicReserveOperatorRepository;

    // market expectations
    @Transient
    Map<ElectricitySpotMarket, MarketInformation> marketInfoMap = new HashMap<ElectricitySpotMarket, MarketInformation>();

    @Override
    public void act(T agent) {

        long futureTimePoint = getCurrentTick() + agent.getInvestmentFutureTimeHorizon();
        // logger.warn(agent + " is looking at timepoint " + futureTimePoint);

        // ==== Expectations ===

        Map<Substance, Double> expectedFuelPrices = predictFuelPrices(agent, futureTimePoint);

        // CO2
        Map<ElectricitySpotMarket, Double> expectedCO2Price = determineExpectedCO2PriceInclTax(futureTimePoint,
                agent.getNumberOfYearsBacklookingForForecasting());

        // logger.warn(expectedCO2Price.toString());

        // Demand
        Map<ElectricitySpotMarket, Double> expectedDemand = new HashMap<ElectricitySpotMarket, Double>();
        for (ElectricitySpotMarket elm : reps.template.findAll(ElectricitySpotMarket.class)) {
            GeometricTrendRegression gtr = new GeometricTrendRegression();
            for (long time = getCurrentTick(); time > getCurrentTick()
                    - agent.getNumberOfYearsBacklookingForForecasting()
                    && time >= 0; time = time - 1) {
                gtr.addData(time, elm.getDemandGrowthTrend().getValue(time));
            }
            expectedDemand.put(elm, gtr.predict(futureTimePoint));
        }

        ElectricitySpotMarket market = agent.getInvestorMarket();
        MarketInformation marketInformation = new MarketInformation(market, expectedDemand, expectedFuelPrices,
                expectedCO2Price.get(market).doubleValue(), futureTimePoint);

        double highestValue = Double.MIN_VALUE;
        PowerGeneratingTechnology bestTechnology = null;

        for (PowerGeneratingTechnology technology : reps.genericRepository.findAll(PowerGeneratingTechnology.class)) {

            PowerPlant plant = new PowerPlant();

            plant.specifyNotPersist(getCurrentTick(), agent, getNodeForZone(market.getZone()), technology, null);

            double expectedInstalledCapacityOfTechnology = reps.powerPlantRepository
                    .calculateCapacityOfExpectedOperationalPowerPlantsInMarketAndTechnology(market, technology,
                            futureTimePoint);
            PowerGeneratingTechnologyTarget technologyTarget = reps.powerGenerationTechnologyTargetRepository
                    .findOneByTechnologyAndMarket(technology, market);
            if (technologyTarget != null) {
                double technologyTargetCapacity = technologyTarget.getTrend().getValue(futureTimePoint);
                expectedInstalledCapacityOfTechnology = (technologyTargetCapacity > expectedInstalledCapacityOfTechnology) ? technologyTargetCapacity
                        : expectedInstalledCapacityOfTechnology;
            }
            // create variable to check if there was a recent failure and
            // technology can be used again

            if (getCurrentTick() >= technology.getAvailableFromTick()) {
                setTechnologyAvailable(technology);
            }

            if ((technology.isCarbonCaptureRequired() != false)
                    && plant.getLocation().getMaximumCcsInNode() - getPlacesLeftForCCS() <= 0) {
                logger.warn(agent + "will not invest in {} technology because there is no more capacity for CCS",
                        technology);
                setTechnologyUnusableForNextTicks(technology, plant.getLocation());
            }

            if (technology.getTechnologyAvailability() != true) {
                logger.warn(agent
                        + "will not invest in {} technology because there was no suitable location previously",
                        technology);

            } else {

                double pgtNodeLimit = Double.MAX_VALUE;
                PowerGeneratingTechnologyNodeLimit pgtLimit = reps.powerGeneratingTechnologyNodeLimitRepository
                        .findOneByTechnologyAndNode(technology, plant.getLocation());
                if (pgtLimit != null) {
                    pgtNodeLimit = pgtLimit.getUpperCapacityLimit(futureTimePoint);
                }
                double expectedInstalledCapacityOfTechnologyInNode = reps.powerPlantRepository
                        .calculateCapacityOfExpectedOperationalPowerPlantsByNodeAndTechnology(plant.getLocation(),
                                technology, futureTimePoint);
                double expectedOwnedTotalCapacityInMarket = reps.powerPlantRepository
                        .calculateCapacityOfExpectedOperationalPowerPlantsInMarketByOwner(market, futureTimePoint,
                                agent);
                double expectedOwnedCapacityInMarketOfThisTechnology = reps.powerPlantRepository
                        .calculateCapacityOfExpectedOperationalPowerPlantsInMarketByOwnerAndTechnology(market,
                                technology, futureTimePoint, agent);
                // logger.warn("Agent {} own capacity in market of technology {} to be"
                // + expectedOwnedCapacityInMarketOfThisTechnology2);
                // logger.warn("Agent {} total capacity of own " +
                // expectedOwnedTotalCapacityInMarket2);

                double capacityOfTechnologyInPipeline = reps.powerPlantRepository
                        .calculateCapacityOfPowerPlantsByTechnologyInPipeline(technology, getCurrentTick());
                double operationalCapacityOfTechnology = reps.powerPlantRepository
                        .calculateCapacityOfOperationalPowerPlantsByTechnology(technology, getCurrentTick());
                double capacityInPipelineInMarket = reps.powerPlantRepository
                        .calculateCapacityOfPowerPlantsByMarketInPipeline(market, getCurrentTick());

                if ((expectedInstalledCapacityOfTechnology + plant.getActualNominalCapacity())
                        / (marketInformation.maxExpectedLoad + plant.getActualNominalCapacity()) > technology
                            .getMaximumInstalledCapacityFractionInCountry()) {
                    // logger.warn(agent +
                    // " will not invest in {} technology because there's too much of this type in the market",
                    // technology);
                } else if ((expectedInstalledCapacityOfTechnologyInNode + plant.getActualNominalCapacity()) > pgtNodeLimit) {

                } else if (expectedOwnedCapacityInMarketOfThisTechnology > expectedOwnedTotalCapacityInMarket
                        * technology.getMaximumInstalledCapacityFractionPerAgent()) {
                    // logger.warn(agent +
                    // " will not invest in {} technology because there's too much capacity planned by him",
                    // technology);
                } else if (capacityInPipelineInMarket > 0.2 * marketInformation.maxExpectedLoad) {
                    // logger.warn("Not investing because more than 20% of demand in pipeline.");

                } else if ((capacityOfTechnologyInPipeline > 2.0 * operationalCapacityOfTechnology)
                        && capacityOfTechnologyInPipeline > 9000) { // TODO:
                    // reflects that you cannot expand a technology out of zero.
                    // logger.warn(agent +
                    // " will not invest in {} technology because there's too much capacity in the pipeline",
                    // technology);
                } else if (plant.getActualInvestedCapital() * (1 - agent.getDebtRatioOfInvestments()) > agent
                        .getDownpaymentFractionOfCash() * agent.getCash()) {
                    // logger.warn(agent +
                    // " will not invest in {} technology as he does not have enough money for downpayment",
                    // technology);
                } else {

                    Map<Substance, Double> myFuelPrices = new HashMap<Substance, Double>();
                    for (Substance fuel : technology.getFuels()) {
                        myFuelPrices.put(fuel, expectedFuelPrices.get(fuel));
                    }
                    Set<SubstanceShareInFuelMix> fuelMix = calculateFuelMix(plant, myFuelPrices,
                            expectedCO2Price.get(market));
                    plant.setFuelMix(fuelMix);

                    double expectedMarginalCost = determineExpectedMarginalCost(plant, expectedFuelPrices,
                            expectedCO2Price.get(market));
                    double runningHours = 0d;
                    double expectedGrossProfit = 0d;

                    long numberOfSegments = reps.segmentRepository.count();

                    for (SegmentLoad segmentLoad : market.getLoadDurationCurve()) {
                        double expectedElectricityPrice = marketInformation.expectedElectricityPricesPerSegment
                                .get(segmentLoad.getSegment());
                        double hours = segmentLoad.getSegment().getLengthInHours();
                        if (expectedMarginalCost <= expectedElectricityPrice) {
                            runningHours += hours;
                            expectedGrossProfit += (expectedElectricityPrice - expectedMarginalCost)
                                    * hours
                                    * plant.getAvailableCapacity(futureTimePoint, segmentLoad.getSegment(),
                                            numberOfSegments);
                        }
                    }

                    // logger.warn(agent +
                    // "expects technology {} to have {} running", technology,
                    // runningHours);
                    // expect to meet minimum running hours?
                    if (runningHours < plant.getTechnology().getMinimumRunningHours()) {
                        // logger.warn(agent+
                        // " will not invest in {} technology as he expect to have {} running, which is lower then required",
                        // technology, runningHours);
                    } else {

                        double fixedOMCost = calculateFixedOperatingCost(plant);// /
                        // plant.getActualNominalCapacity();

                        double operatingProfit = expectedGrossProfit - fixedOMCost;

                        double permitRisk = technology.getPreviousPermitExperience();

                        double wacc = ((1 - agent.getDebtRatioOfInvestments()) * agent.getEquityInterestRate() + agent
                                .getDebtRatioOfInvestments() * agent.getLoanInterestRate())
                                * permitRisk;

                        // logger.warn("Agent {} found for technology {} the factor"
                        // + multiFactorWacc, agent, technology);

                        // Creation of out cash-flow during power plant building
                        // phase (note that the cash-flow is negative!)
                        TreeMap<Integer, Double> discountedProjectCapitalOutflow = calculateSimplePowerPlantInvestmentCashFlow(
                                technology.getDepreciationTime(), (int) plant.getActualLeadtime(),
                                plant.getActualInvestedCapital(), 0);

                        TreeMap<Integer, Double> discountedProjectCashInflow = calculateSimplePowerPlantInvestmentCashFlow(
                                technology.getDepreciationTime(), (int) plant.getActualLeadtime(), 0, operatingProfit);

                        double discountedCapitalCosts = npv(discountedProjectCapitalOutflow, wacc);

                        double discountedOpProfit = npv(discountedProjectCashInflow, wacc);

                        double projectValue = discountedOpProfit + discountedCapitalCosts;

                        // Delayed NPV for potential delay in construction due
                        // to activism and lawsuits

                        TreeMap<Integer, Double> discountedProjectCapitalOutflowDelay = calculateSimplePowerPlantInvestmentCashFlow(
                                technology.getDepreciationTime(), (int) plant.getActualLeadtime2(),
                                plant.getActualInvestedCapitalDelay(), 0);

                        TreeMap<Integer, Double> discountedProjectCashInflowDelay = calculateSimplePowerPlantInvestmentCashFlow(
                                technology.getDepreciationTime(), (int) plant.getActualLeadtime2(), 0, operatingProfit);

                        double discountedCapitalCostsDelay = npv(discountedProjectCapitalOutflowDelay, wacc);

                        double discountedOpProfitDelay = npv(discountedProjectCashInflowDelay, wacc);

                        double projectValueDelay = discountedOpProfitDelay + discountedCapitalCostsDelay;

                        /*
                         * Divide by capacity, in order not to favour large
                         * power plants (which have the single largest NPV
                         */

                        if (projectValue > 0 && projectValue / plant.getActualNominalCapacity() > highestValue) {
                            highestValue = projectValue / plant.getActualNominalCapacity();
                            technology.setNpv(projectValue);
                            technology.setNpvDelay(projectValueDelay);
                            bestTechnology = plant.getTechnology();
                        }
                    }

                }
            }
        }

        // Location site identification for technology of choice bestTechnology
        // For all location check if they are suitable for a certain technology
        // and have room for a new plant if so calculate utility and rank
        // location top 3 according to utility

        PowerGridNode zone = getNodeForZone(market.getZone());

        if (bestTechnology != null) {

            logger.warn(agent + "starts to look for locations for technology {} with project value {}", bestTechnology,
                    bestTechnology.getNpv());

            // Hashmap entries to save and sort them later
            HashMap<Location, Double> locationAndUtilityMap = new HashMap<Location, Double>();
            MapValueReverseComparator rankingComparator = new MapValueReverseComparator(locationAndUtilityMap);
            TreeMap<Location, Double> sortedLocationAndUtilityMap = new TreeMap<Location, Double>(rankingComparator);

            for (Location siteLocation : reps.genericRepository.findAll(Location.class)) {

                if (siteLocation.getLocationFailureTimer() <= getCurrentTick()) {

                    if (bestTechnology.getName().equals("Wind") || bestTechnology.getName().equals("WindOffshore")
                            || bestTechnology.getName().equals("Photovoltaic")) {
                        if (bestTechnology.getName().equals("Wind")) {
                            if (siteLocation.isFeedstockAvailabilityWind() == false) {
                            } else if (siteLocation.getPossiblePlants()
                                    - calculateNumberOfPlantsAtLocationOld(siteLocation, getCurrentTick()) <= 0) {
                            } else {
                                siteLocation.setUtilityLocation(calculateAndSetUtilityLocationWind(agent, siteLocation,
                                        zone));
                                locationAndUtilityMap.put(siteLocation, siteLocation.getUtilityLocation());
                            }
                        } else if (bestTechnology.getName().equals("WindOffshore")) {
                            if (siteLocation.isOffShore() == false) {
                            } else if (siteLocation.getPossiblePlants()
                                    - calculateNumberOfPlantsAtLocationOld(siteLocation, getCurrentTick()) <= 0) {
                            } else {
                                siteLocation.setUtilityLocation(calculateAndSetUtilityLocationWindOffShore(agent,
                                        siteLocation, zone));
                                locationAndUtilityMap.put(siteLocation, siteLocation.getUtilityLocation());
                            }
                        } else if (bestTechnology.getName().equals("Photovoltaic")) {
                            if (siteLocation.isFeedstockAvailabilitySun() == false) {
                            } else if (siteLocation.getPossiblePlants()
                                    - calculateNumberOfPlantsAtLocation(siteLocation, getCurrentTick()) <= 0) {
                            } else {
                                siteLocation.setUtilityLocation(calculateAndSetUtilityLocationSun(agent, siteLocation,
                                        zone));
                                locationAndUtilityMap.put(siteLocation, siteLocation.getUtilityLocation());
                            }
                        }
                    } else {
                        boolean ccsTechMatchesWithLocation = false;

                        if (bestTechnology.isCarbonCaptureRequired() != false
                                && siteLocation.isCarbonCaptureStorageAvailability() != false) {
                            ccsTechMatchesWithLocation = true;
                        }

                        if (ccsTechMatchesWithLocation != false || bestTechnology.isCarbonCaptureRequired() != true) {

                            for (Substance fuel : siteLocation.getfeedstockAvailabilityList()) {
                                if (fuel.getName().equals(bestTechnology.getMainFuel().getName())
                                        && (siteLocation.getPossiblePlants()
                                                - calculateNumberOfPlantsAtLocation(siteLocation, getCurrentTick()) >= 0)) {
                                    siteLocation.setUtilityLocation(calculateAndSetUtilityLocation(agent, siteLocation,
                                            zone));
                                    locationAndUtilityMap.put(siteLocation, siteLocation.getUtilityLocation());
                                } else {
                                    logger.warn("location {} is not suitable for technology" + bestTechnology,
                                            siteLocation);
                                }

                            }
                        }
                    }
                } else {
                    logger.warn("location {} not used because it recently failed permit negotiations", siteLocation);
                }
            }

            System.out.println("unsorted map: " + locationAndUtilityMap);
            // Sorts location from high to low

            sortedLocationAndUtilityMap.putAll(locationAndUtilityMap);

            System.out.println("sorted map: " + sortedLocationAndUtilityMap);

            // Delete all map entries until the amount of locations that can be
            // assessed in the location permit procedure is reached
            while (sortedLocationAndUtilityMap.size() > zone.getNumberOfLocationAssessed()
                    || locationAndUtilityMap.size() > zone.getNumberOfLocationAssessed()) {
                locationAndUtilityMap.clear();
                Location locToRemove = sortedLocationAndUtilityMap.lastKey();
                locationAndUtilityMap.putAll(sortedLocationAndUtilityMap);
                locationAndUtilityMap.remove(locToRemove);
                sortedLocationAndUtilityMap.clear();
                sortedLocationAndUtilityMap.putAll(locationAndUtilityMap);
                logger.warn("size of map " + sortedLocationAndUtilityMap.size());
            }

            System.out.println("sorted map after reduction in size: " + sortedLocationAndUtilityMap);

            if (sortedLocationAndUtilityMap.size() != 0) {
            } else {
                setTechnologyUnusableForNextTicks(bestTechnology, zone);
            }

            // Permit procedure, opposition calculations locations and payoff
            // using
            // nucleolus theories
            boolean LocationChosen = false;
            boolean permitFailure = false;

            // create empty chosen location

            Location chosenLocation = null;

            Location locationrank1 = null;

            while (LocationChosen == false && permitFailure == false) {

                if (sortedLocationAndUtilityMap.size() > 0) {
                    locationrank1 = sortedLocationAndUtilityMap.firstKey();
                } else {
                    locationrank1 = null;
                }

                logger.warn(locationrank1 + "location permit applied for");

                if (locationrank1 != null) {
                    setNumberOfPermitApplications(locationrank1);

                    // create variables for the utility functions
                    double compensationGovernment = 0d;
                    double compensationElectricityProducer = 0d;
                    double compensationLocalParty = 0d;
                    double utilityElectricityProducer = 0d;
                    agent.setCompensationElectricityProducer(0);

                    // empty authorized government
                    LocalGovernment authorizedGovernment = null;

                    // linking to right government
                    for (LocalGovernment localgov : reps.genericRepository.findAll(LocalGovernment.class)) {
                        if (localgov.getName().equals(locationrank1.getProvince())) {
                            authorizedGovernment = localgov;
                        }
                    }

                    double plantsOfTechnology = getAmountofPlantsinProvinceforTechnology(authorizedGovernment,
                            bestTechnology, getCurrentTick());

                    // Environmental compensation to Local government

                    double utilityGovernment = calculateAndSetUtilityGovernment(authorizedGovernment, bestTechnology,
                            compensationGovernment, plantsOfTechnology, zone);

                    logger.warn(authorizedGovernment.getName()
                            + " has an utility for the proposed plant of {} with costs of {} ", utilityGovernment,
                            bestTechnology.getEnvironmentalCosts());
                    // compensation payments until local government is no worse
                    // off
                    // than when nothing would have been build

                    while (utilityGovernment <= 0) {
                        compensationGovernment += zone.getCompensationGovernment();
                        compensationElectricityProducer -= zone.getCompensationGovernment();
                        utilityGovernment = calculateAndSetUtilityGovernment(authorizedGovernment, bestTechnology,
                                compensationGovernment, plantsOfTechnology, zone);
                        utilityElectricityProducer = (bestTechnology.getNpv() + compensationElectricityProducer)
                                / bestTechnology.getNpv();
                    }

                    logger.warn("payed to government:" + compensationGovernment);

                    // generate random number of inhabitants based on normal
                    // distribution and environmental factors
                    if (locationrank1.isOffShore() != true) {
                        Random rand = new Random();
                        double normalDistribution = rand.nextGaussian();
                        double sigmaNormalDistribution = (((locationrank1.getPopulationDensity() - zone
                                .getMinPopulationDensity()) / zone.getDeltaPopulationDensity())
                                * authorizedGovernment.getWeightFactorDensity()
                                + ((locationrank1.getWealth() - zone.getMinWealth()) / zone.getDeltaWealth())
                                * authorizedGovernment.getWeightFactorWealth() + ((bestTechnology
                                .getTechnologyPreference() - zone.getMaxTechnologyPreference()) / zone
                                .getDeltaTechnologyPreference())
                                * authorizedGovernment.getWeightFactorPreference());
                        double numberOfLocals = Math.abs(Math.floor(normalDistribution * sigmaNormalDistribution));

                        setPreviousPermitExperience(bestTechnology, numberOfLocals);
                        // create empty list of local parties
                        // create local parties
                        logger.warn(agent + " encountered a number of local parties of {}", numberOfLocals);

                        double investmentCost = bestTechnology.getInvestmentCost(getCurrentTick())
                                * bestTechnology.getCapacity();

                        // Utility compensation for locals will be based on s
                        // function
                        // 1/1+exp(-x) which will be forced in the interval -10,
                        // 10
                        // to
                        // be a nice s shaped function

                        // Hashmap entries to use for selecting unhappiest party
                        HashMap<LocationLocalParties, Double> localPartiesUtilityMap = new HashMap<LocationLocalParties, Double>();
                        MapValueComparator rankingComparatorLocalParties = new MapValueComparator(
                                localPartiesUtilityMap);
                        TreeMap<LocationLocalParties, Double> sortedLocalPartiesUtilityMap = new TreeMap<LocationLocalParties, Double>(
                                rankingComparatorLocalParties);

                        for (int i = 0; i < numberOfLocals; i++) {
                            double utility = 0d;
                            Random randomnumber = new Random();
                            LocationLocalParties local = new LocationLocalParties();
                            local.setName("Party" + i);
                            local.setFactorRandomParty((Math.abs(0.25 * randomnumber.nextGaussian() + 1)));
                            utility = calculateAndSetUtilityLocalParty(bestTechnology, locationrank1, local,
                                    investmentCost, zone);
                            local.setUtility(utility);
                            logger.warn(local.getName() + " (A) Has Utility of {}", local.getUtilityLocalParty());
                            localPartiesUtilityMap.put(local, utility);
                        }

                        System.out.println("unsorted map: " + localPartiesUtilityMap);
                        // Sorts local parties from low to high

                        sortedLocalPartiesUtilityMap.putAll(localPartiesUtilityMap);

                        System.out.println("sorted map: " + sortedLocalPartiesUtilityMap);

                        // compensate local parties

                        double averageUtility = -1;
                        if (sortedLocalPartiesUtilityMap.size() > 0 && utilityElectricityProducer >= 0) {
                            while (agent.getRiskAcceptance() >= averageUtility) {

                                // if (resortedLocalPartiesUtilityMap.size() >
                                // 0) {
                                // sortedLocalPartiesUtilityMap =
                                // resortedLocalPartiesUtilityMap;
                                // }

                                System.out.println("sorted map: " + sortedLocalPartiesUtilityMap);

                                double unhappiestPartyUtility = 0d;

                                LocationLocalParties unhappiestLocalParty = sortedLocalPartiesUtilityMap.firstKey();

                                sortedLocalPartiesUtilityMap.clear();

                                unhappiestLocalParty.setCompensationLocalParty(unhappiestLocalParty
                                        .getCompensationLocalParty() + zone.getCompensationLocals());
                                compensationLocalParty += zone.getCompensationLocals();

                                compensationElectricityProducer -= zone.getCompensationLocals();
                                unhappiestLocalParty.setUtility(calculateAndSetUtilityLocalParty(bestTechnology,
                                        locationrank1, unhappiestLocalParty, investmentCost, zone));

                                unhappiestPartyUtility = calculateAndSetUtilityLocalParty(bestTechnology,
                                        locationrank1, unhappiestLocalParty, investmentCost, zone);
                                unhappiestLocalParty.setUtility(unhappiestPartyUtility);

                                logger.warn(
                                        "local party {} has received and now has utility of {} and got compensation"
                                                + unhappiestLocalParty.getCompensationLocalParty(),
                                        unhappiestLocalParty.getName(), unhappiestLocalParty.getUtilityLocalParty());

                                localPartiesUtilityMap.put(unhappiestLocalParty, unhappiestPartyUtility);

                                System.out.println("unsorted map: " + localPartiesUtilityMap);

                                sortedLocalPartiesUtilityMap.putAll(localPartiesUtilityMap);

                                System.out.println("sorted map: " + sortedLocalPartiesUtilityMap);

                                double sumLocalUtilities = 0d;

                                for (double i : sortedLocalPartiesUtilityMap.values()) {
                                    sumLocalUtilities += i;
                                }

                                averageUtility = sumLocalUtilities / sortedLocalPartiesUtilityMap.size();
                                logger.warn(averageUtility + " Average utility is with amount of locals {}",
                                        sortedLocalPartiesUtilityMap.size());
                                locationrank1.setAverageUtility(averageUtility);

                                // update utility function electricity producer,
                                // based
                                // on
                                // npv from technology and maximum delay
                                // possible

                                double minimalUtility = 0;
                                utilityElectricityProducer = (((bestTechnology.getNpv() + compensationElectricityProducer) - (-Math
                                        .min(minimalUtility, averageUtility) * (locationrank1.getCourtChance() * (bestTechnology
                                        .getNpv() - bestTechnology.getNpvDelay())))) - 0)
                                        / (bestTechnology.getNpv());
                                agent.setCompensationElectricityProducer(compensationElectricityProducer);
                            }
                            logger.warn(averageUtility
                                    + "is now the average utility of the locals and agent {} payed {} to reach this",
                                    agent, compensationElectricityProducer);
                        }
                    } else {
                        utilityElectricityProducer = (bestTechnology.getNpv() + compensationElectricityProducer)
                                / bestTechnology.getNpv();
                        agent.setCompensationElectricityProducer(compensationElectricityProducer);
                    }

                    if (utilityElectricityProducer > 0) {
                        LocationChosen = true;
                        chosenLocation = locationrank1;
                        setLocalCompensationAtLocation(chosenLocation, compensationLocalParty);
                    } else {
                        sortedLocationAndUtilityMap.remove(sortedLocationAndUtilityMap.firstKey());
                        logger.warn("removed {} from map because permit failed", locationrank1);
                        System.out.println("Sorted map now looks like" + sortedLocationAndUtilityMap);
                        setLocationPermitFailure(locationrank1, zone);
                        logger.warn(
                                "permit failure so variable for location {} is " + locationrank1.getLocationFailure(),
                                locationrank1);
                    }

                } else {
                    // logger.warn(agent +
                    // " did not invest in technology {} , because the permit negotiation failed",
                    // bestTechnology);

                    // do not use technology for X(predefined) time
                    setTechnologyUnusableForNextTicks(bestTechnology, zone);
                    permitFailure = true;

                }
            }

            if (LocationChosen != false) {
                logger.warn("Agent {} invested in technology {} at tick " + getCurrentTick(), agent, bestTechnology);
                logger.warn(agent + "invested in technology {} at location {}", bestTechnology,
                        chosenLocation.getName());

                setTotalCompensationPayedForTechnology(bestTechnology, agent);
                setTotalCompensationPayedAtLocation(chosenLocation, agent);

                PowerPlant plant = new PowerPlant();

                double chanceOnDelay = Math.random();
                if (chanceOnDelay > ((chosenLocation.getAverageUtility()) * -1)) {
                    chanceOnDelay = 0;
                } else {
                    chanceOnDelay = 1;
                }

                plant.setActualLeadtime(plant.getActualLeadtime() + (long) chanceOnDelay
                        * (plant.getActualLeadtime2() - plant.getActualLeadtime()));
                plant.specifyAndPersist(getCurrentTick(), agent, getNodeForZone(market.getZone()), bestTechnology,
                        chosenLocation);
                PowerPlantManufacturer manufacturer = reps.genericRepository.findFirst(PowerPlantManufacturer.class);
                BigBank bigbank = reps.genericRepository.findFirst(BigBank.class);

                double additionalCostPermit = agent.getCompensationElectricityProducer();

                double investmentCostPayedByEquity = (plant.getActualInvestedCapital() + additionalCostPermit)
                        * (1 - agent.getDebtRatioOfInvestments());
                double investmentCostPayedByDebt = (plant.getActualInvestedCapital() + additionalCostPermit)
                        * agent.getDebtRatioOfInvestments();
                double downPayment = investmentCostPayedByEquity;
                createSpreadOutDownPayments(agent, manufacturer, downPayment, plant);

                double amount = determineLoanAnnuities(investmentCostPayedByDebt, plant.getTechnology()
                        .getDepreciationTime(), agent.getLoanInterestRate());
                // logger.warn("Loan amount is: " + amount);
                Loan loan = reps.loanRepository.createLoan(agent, bigbank, amount, plant.getTechnology()
                        .getDepreciationTime(), getCurrentTick(), plant);
                // Create the loan
                plant.createOrUpdateLoan(loan);

            }
        } else {
            logger.warn("{} found no suitable technology anymore to invest in at tick " + getCurrentTick(), agent);
            // agent will not participate in the next round of investment if he
            // does not invest now
            setNotWillingToInvest(agent);
        }

    }

    // }

    // Creates n downpayments of equal size in each of the n building years of a
    // power plant
    @Transactional
    private void createSpreadOutDownPayments(EnergyProducer agent, PowerPlantManufacturer manufacturer,
            double totalDownPayment, PowerPlant plant) {
        int buildingTime = (int) plant.getActualLeadtime();
        reps.nonTransactionalCreateRepository.createCashFlow(agent, manufacturer, totalDownPayment / buildingTime,
                CashFlow.DOWNPAYMENT, getCurrentTick(), plant);
        Loan downpayment = reps.loanRepository.createLoan(agent, manufacturer, totalDownPayment / buildingTime,
                buildingTime - 1, getCurrentTick(), plant);
        plant.createOrUpdateDownPayment(downpayment);
    }

    @Transactional
    private void setNotWillingToInvest(EnergyProducer agent) {
        agent.setWillingToInvest(false);
    }

    @Transactional
    private void setTechnologyUnusableForNextTicks(PowerGeneratingTechnology bestTechnology, PowerGridNode zone) {
        for (PowerGeneratingTechnology technology : reps.genericRepository.findAll(PowerGeneratingTechnology.class)) {
            if (bestTechnology.getName().equals(technology.getName())) {
                technology.setTechnologyAvailability(false);
                technology.setAvailableFromTick(getCurrentTick() + zone.getTimeNotUsingTech());
            }
        }
    }

    @Transactional
    private void setNumberOfPermitApplications(Location locationrank1) {
        for (Location loc : reps.genericRepository.findAll(Location.class)) {
            if (locationrank1.getName().equals(loc.getName())) {
                loc.setPermitTries(loc.getPermitTries() + 1);
            }
        }
    }

    @Transactional
    private void setTotalCompensationPayedForTechnology(PowerGeneratingTechnology bestTechnology, EnergyProducer agent) {
        for (PowerGeneratingTechnology technology : reps.genericRepository.findAll(PowerGeneratingTechnology.class)) {
            if (bestTechnology.getName().equals(technology.getName())) {
                technology.setCompensationPayedTechnology(technology.getCompensationPayedTechnology()
                        + agent.getCompensationElectricityProducer());
                technology.setNumberOfInvestmentsInTechnology(technology.getNumberOfInvestmentsInTechnology() + 1);

            }

        }
    }

    @Transactional
    private void setLocalCompensationAtLocation(Location locationrank1, double comp) {
        locationrank1.setLocalPartyCompensation(locationrank1.getLocalPartyCompensation() + comp);
    }

    @Transactional
    private void setTotalCompensationPayedAtLocation(Location locationrank1, EnergyProducer agent) {
        for (Location loc : reps.genericRepository.findAll(Location.class)) {
            if (locationrank1.getName().equals(loc.getName())) {
                loc.setTotalCompensationPayed(loc.getTotalCompensationPayed()
                        + agent.getCompensationElectricityProducer());

            }
        }
    }

    @Transactional
    private void setLocationPermitFailure(Location locationrank1, PowerGridNode zone) {
        for (Location loc : reps.genericRepository.findAll(Location.class)) {
            if (locationrank1.getName().equals(loc.getName())) {
                loc.setLocationFailure(0);
                loc.setLocationFailureTimer(getCurrentTick() + zone.getTimeNotUsingLocation());
                loc.setCountPermitFailures(loc.getCountPermitFailures() + 1);
            }
        }
    }

    @Transactional
    private void setPreviousPermitExperience(PowerGeneratingTechnology bestTechnology, double AmountOfLocals) {
        for (PowerGeneratingTechnology technology : reps.genericRepository.findAll(PowerGeneratingTechnology.class)) {
            if (bestTechnology.getName().equals(technology.getName())) {
                if (AmountOfLocals <= 5) {
                    technology.setPreviousPermitExperience(1);
                } else if (AmountOfLocals <= 10) {
                    technology.setPreviousPermitExperience(1.05);
                } else if (AmountOfLocals <= 15) {
                    technology.setPreviousPermitExperience(1.1);
                } else if (AmountOfLocals >= 20) {
                    technology.setPreviousPermitExperience(1.3);
                }

            }
        }
    }

    @Transactional
    private void setTechnologyAvailable(PowerGeneratingTechnology technology) {
        technology.setTechnologyAvailability(true);
        technology.setAvailableFromTick(0);
    }

    /**
     * Predicts fuel prices for {@link futureTimePoint} using a geometric trend
     * regression forecast. Only predicts fuels that are traded on a commodity
     * market.
     * 
     * @param agent
     * @param futureTimePoint
     * @return Map<Substance, Double> of predicted prices.
     */
    public Map<Substance, Double> predictFuelPrices(EnergyProducer agent, long futureTimePoint) {
        // Fuel Prices
        Map<Substance, Double> expectedFuelPrices = new HashMap<Substance, Double>();
        for (Substance substance : reps.substanceRepository.findAllSubstancesTradedOnCommodityMarkets()) {
            // Find Clearing Points for the last 5 years (counting current year
            // as one of the last 5 years).
            Iterable<ClearingPoint> cps = reps.clearingPointRepository
                    .findAllClearingPointsForSubstanceTradedOnCommodityMarkesAndTimeRange(substance, getCurrentTick()
                            - (agent.getNumberOfYearsBacklookingForForecasting() - 1), getCurrentTick());
            // logger.warn("{}, {}",
            // getCurrentTick()-(agent.getNumberOfYearsBacklookingForForecasting()-1),
            // getCurrentTick());
            // Create regression object
            GeometricTrendRegression gtr = new GeometricTrendRegression();
            for (ClearingPoint clearingPoint : cps) {
                // logger.warn("CP {}: {} , in" + clearingPoint.getTime(),
                // substance.getName(), clearingPoint.getPrice());
                gtr.addData(clearingPoint.getTime(), clearingPoint.getPrice());
            }
            expectedFuelPrices.put(substance, gtr.predict(futureTimePoint));
            // logger.warn("Forecast {}: {}, in Step " + futureTimePoint,
            // substance, expectedFuelPrices.get(substance));
        }
        return expectedFuelPrices;
    }

    // Create a powerplant investment and operation cash-flow in the form of a
    // map. If only investment, or operation costs should be considered set
    // totalInvestment or operatingProfit to 0
    private TreeMap<Integer, Double> calculateSimplePowerPlantInvestmentCashFlow(int depriacationTime,
            int buildingTime, double totalInvestment, double operatingProfit) {
        TreeMap<Integer, Double> investmentCashFlow = new TreeMap<Integer, Double>();
        double equalTotalDownPaymentInstallement = totalInvestment / buildingTime;
        for (int i = 0; i < buildingTime; i++) {
            investmentCashFlow.put(new Integer(i), -equalTotalDownPaymentInstallement);
        }
        for (int i = buildingTime; i < depriacationTime + buildingTime; i++) {
            investmentCashFlow.put(new Integer(i), operatingProfit);
        }

        return investmentCashFlow;
    }

    private double npv(TreeMap<Integer, Double> netCashFlow, double wacc) {
        double npv = 0;
        for (Integer iterator : netCashFlow.keySet()) {
            npv += netCashFlow.get(iterator).doubleValue() / Math.pow(1 + wacc, iterator.intValue());
        }
        return npv;
    }

    public double determineExpectedMarginalCost(PowerPlant plant, Map<Substance, Double> expectedFuelPrices,
            double expectedCO2Price) {
        double mc = determineExpectedMarginalFuelCost(plant, expectedFuelPrices);
        double co2Intensity = plant.calculateEmissionIntensity();
        mc += co2Intensity * expectedCO2Price;
        return mc;
    }

    public double determineExpectedMarginalFuelCost(PowerPlant powerPlant, Map<Substance, Double> expectedFuelPrices) {
        double fc = 0d;
        for (SubstanceShareInFuelMix mix : powerPlant.getFuelMix()) {
            double amount = mix.getShare();
            double fuelPrice = expectedFuelPrices.get(mix.getSubstance());
            fc += amount * fuelPrice;
        }
        return fc;
    }

    private PowerGridNode getNodeForZone(Zone zone) {
        for (PowerGridNode node : reps.genericRepository.findAll(PowerGridNode.class)) {
            if (node.getZone().equals(zone)) {
                return node;
            }
        }
        return null;
    }

    public double getPlacesLeftForCCS() {
        double ccsPlants = 0d;

        for (PowerPlant pp : reps.genericRepository.findAll(PowerPlant.class)) {
            if (pp.getTechnology().getName().equals("IgccCCS") || pp.getTechnology().getName().equals("CcgtCCS")) {
                ccsPlants += 1;
            } else if (pp.getTechnology().getName().equals("CoalPscCSS")) {
                ccsPlants += 2;
            }
        }

        // for (PowerGeneratingTechnology tech :
        // reps.genericRepository.findAll(PowerGeneratingTechnology.class)) {

        // if (tech.getName().equals("CoalPscCSS") ||
        // tech.getName().equals("IgccCCS")
        // || tech.getName().equals("CcgtCCS")) {
        // double numberPlants =
        // reps.powerPlantRepository.countPowerPlantsBytechnology(tech);
        // if (tech.getName().equals("CoalPscCCS")) {
        // numberPlants = 2 * numberPlants;
        // }
        // ccsPlants += numberPlants;
        // }
        // }

        return ccsPlants;
    }

    public double calculateAndSetUtilityLocation(EnergyProducer agent, Location siteLocation, PowerGridNode zone) {
        double utilityLocationUpForValuation = (((siteLocation.getPopulationDensity() - zone.getMaxPopulationDensity()) / zone
                .getDeltaPopulationDensity())
                * -1
                * agent.getWeightFactorDensity()
                + (((siteLocation.getWealth() - zone.getMaxWealth()) / zone.getDeltaWealth()))
                * -1
                * agent.getWeightFactorWealth()
                + (((siteLocation.getDistanceGrid() - zone.getMaxDistanceGrid()) / zone.getDeltaDistanceGrid()))
                * -1
                * agent.getWeightFactorDistance() + ((siteLocation.getQualityWater() - zone.getMinQualityWater()) / zone
                .getDeltaQualityWater()) * agent.getWeightFactorFeedstock());
        return utilityLocationUpForValuation;
    }

    public double calculateAndSetUtilityLocationWind(EnergyProducer agent, Location siteLocation, PowerGridNode zone) {
        double utilityLocationUpForValuation = (((siteLocation.getPopulationDensity() - zone.getMaxPopulationDensity()) / zone
                .getDeltaPopulationDensity())
                * -1
                * agent.getWeightFactorDensity()
                + (((siteLocation.getWealth() - zone.getMaxWealth()) / zone.getDeltaWealth()))
                * -1
                * agent.getWeightFactorWealth()
                + (((siteLocation.getDistanceGrid() - zone.getMaxDistanceGrid()) / zone.getDeltaDistanceGrid()))
                * -1
                * agent.getWeightFactorDistance() + ((siteLocation.getWindPower() - zone.getMinWindPower()) / zone
                .getDeltaWindPower()) * agent.getWeightFactorFeedstock());
        return utilityLocationUpForValuation;
    }

    public double calculateAndSetUtilityLocationWindOffShore(EnergyProducer agent, Location siteLocation,
            PowerGridNode zone) {
        double utilityLocationUpForValuation = (((siteLocation.getWindPower() - zone.getMinWindPower()) / zone
                .getDeltaWindPower())
                * agent.getWeightFactorFeedstock()
                + ((siteLocation.getDepthWater() - zone.getMaxWaterDepth()) / zone.getDeltaWaterDepth())
                * -1
                * agent.getWeightFactorDepthWater() + ((siteLocation.getDistanceShore() - zone.getMaxDistanceShore()) / zone
                .getDeltaDistanceShore()) * -1 * agent.getWeightFactorDistanceShore());
        return utilityLocationUpForValuation;
    }

    public double calculateAndSetUtilityLocationSun(EnergyProducer agent, Location siteLocation, PowerGridNode zone) {
        double utilityLocationUpForValuation = (((siteLocation.getPopulationDensity() - zone.getMaxPopulationDensity()) / zone
                .getDeltaPopulationDensity())
                * -1
                * agent.getWeightFactorDensity()
                + (((siteLocation.getWealth() - zone.getMaxWealth()) / zone.getDeltaWealth()))
                * -1
                * agent.getWeightFactorWealth()
                + (((siteLocation.getDistanceGrid() - zone.getMaxDistanceGrid()) / zone.getDeltaDistanceGrid()))
                * -1
                * agent.getWeightFactorDistance() + ((siteLocation.getSunHours() - zone.getMinSunHours()) / zone
                .getDeltaSunHours()) * agent.getWeightFactorFeedstock());
        return utilityLocationUpForValuation;
    }

    public double calculateAndSetUtilityGovernment(LocalGovernment authorizedGovernment,
            PowerGeneratingTechnology bestTechnology, double compensationGovernment, double plantsOfTechnology,
            PowerGridNode zone) {
        double utilityGovernment = 0d;
        if (bestTechnology.getName().equals("Wind") || bestTechnology.getName().equals("WindOffshore")) {
            utilityGovernment = (((bestTechnology.getEnvironmentalCosts() - zone.getMinEnvironmentalCosts()) / (zone
                    .getDeltaEnvironmentalCosts()))
                    * -1
                    * authorizedGovernment.getWeightEnvironment()
                    + ((bestTechnology.getEmployment() - zone.getMinEmployment()) / zone.getDeltaEmployment())
                    * authorizedGovernment.getWeightEmployment()
                    + ((plantsOfTechnology - zone.getMinPlantsOfTechnology()) / zone.getDeltaPlantsOfTechnologyWind())
                    * -1 * authorizedGovernment.getWeightPrevious() + (compensationGovernment / (bestTechnology
                    .getInvestmentCost(getCurrentTick()) * bestTechnology.getCapacity() * zone
                        .getMaxCompensationGovernmentPercentageOfInvestment()))
                    * authorizedGovernment.getWeightCompensation());
        } else {
            utilityGovernment = (((bestTechnology.getEnvironmentalCosts() - zone.getMinEnvironmentalCosts()) / (zone
                    .getDeltaEnvironmentalCosts()))
                    * -1
                    * authorizedGovernment.getWeightEnvironment()
                    + ((bestTechnology.getEmployment() - zone.getMinEmployment()) / zone.getDeltaEmployment())
                    * authorizedGovernment.getWeightEmployment()
                    + ((plantsOfTechnology - zone.getMinPlantsOfTechnology()) / zone.getDeltaPlantsOfTechnology())
                    * -1
                    * authorizedGovernment.getWeightPrevious() + (compensationGovernment / (bestTechnology
                    .getInvestmentCost(getCurrentTick()) * bestTechnology.getCapacity() * zone
                        .getMaxCompensationGovernmentPercentageOfInvestment()))
                    * authorizedGovernment.getWeightCompensation());
        }
        return utilityGovernment;
    }

    public double calculateAndSetUtilityLocalParty(PowerGeneratingTechnology tech, Location site,
            LocationLocalParties local, double investmentCost, PowerGridNode zone) {
        double min = -1;

        double utilityLocals = (Math.max(
                min,
                (local.getFactorRandomParty() * (((site.getPopulationDensity() - zone.getMinPopulationDensity()) / zone
                        .getDeltaPopulationDensity())
                        * -1
                        * site.getWeightFactorDensity()
                        + ((site.getWealth() - zone.getMinWealth()) / zone.getDeltaWealth())
                        * -1
                        * site.getWeightFactorWealth() + ((tech.getTechnologyPreference() - zone
                        .getMaxTechnologyPreference()) / zone.getDeltaTechnologyPreference())
                        * site.getWeightFactorTechPref()))) + ((1 / (1 + Math.exp(-((((local
                .getCompensationLocalParty()) / (investmentCost * site.getEffectivenessCompensation())) * 20) - 10)))) * site
                .getWeightFactorCompensation()));

        // logger.warn((((site.getPopulationDensity() - 21) / 6110) * -1 *
        // site.getWeightFactorDensity())
        // + " population density {} and factor {} ",
        // site.getPopulationDensity(), site.getWeightFactorDensity());
        // logger.warn(((site.getWealth() - 10.8) / 12.4) * -1 *
        // site.getWeightFactorWealth() + " wealth");
        // logger.warn((((tech.getTechnologyPreference() - 61) / 57) *
        // site.getWeightFactorTechPref()) + " tech pref");
        // logger.warn(((1 / (1 +
        // Math.exp(-((((local.getCompensationLocalParty()) / (investmentCost *
        // site
        // .getEffectivenessCompensation())) * 20) - 10)))) *
        // site.getWeightFactorCompensation()) + "compensation");
        return utilityLocals;
    }

    public double getAmountofPlantsinProvinceforTechnology(LocalGovernment authorizedGovernment,
            PowerGeneratingTechnology bestTechnology, long time) {
        double AmountOfPlants = 0;
        for (Location ppLocation : reps.genericRepository.findAll(Location.class)) {
            if (ppLocation.getProvince().equals(authorizedGovernment.getName())) {

                for (PowerPlant plant : reps.powerPlantRepository
                        .findOperationalPowerPlantsByLocation(ppLocation, time)) {

                    if (bestTechnology.getFeedstockID().equals(plant.getTechnology().getFeedstockID())) {
                        AmountOfPlants += 1;
                    } else {

                    }
                }
            }
        }

        return AmountOfPlants;
    }

    public double calculateTechnologyMarketShare(EnergyProducer producer, PowerGeneratingTechnology technology,
            long time) {

        String i = technology.getName();
        double technologyCapacity = 0d;

        for (PowerPlant plant : reps.powerPlantRepository.findOperationalPowerPlantsByOwner(producer, time)) {

            if (plant.getTechnology().getName().equals(i)) {

                technologyCapacity += plant.getActualNominalCapacity();

            } else {

            }

        }

        return technologyCapacity;
    }

    public double calculateNumberOfPlantsAtLocationOld(Location siteLocation, long time) {
        String i = siteLocation.getName();
        double AmountOfPlantsAtLocation = 0d;

        for (PowerPlant plant : reps.powerPlantRepository.findNonDismantledPowerPlantsByLocation(siteLocation, time)) {
            if (plant.getSiteLocation().getName().equals(i)) {
                AmountOfPlantsAtLocation += 1;
            } else {

            }
        }

        return AmountOfPlantsAtLocation;
    }

    public double calculateNumberOfPlantsAtLocation(Location siteLocation, long time) {
        String i = siteLocation.getName();
        double AmountOfPlantsAtLocation = 0d;

        for (PowerPlant plant : reps.powerPlantRepository.findNonDismantledPowerPlantsByLocation(siteLocation, time)) {
            if (plant.getSiteLocation().getName().equals(i)) {
                AmountOfPlantsAtLocation += (plant.getTechnology().getCapacity()) / 500;
            } else {

            }
        }

        return AmountOfPlantsAtLocation;
    }

    private class MarketInformation {

        Map<Segment, Double> expectedElectricityPricesPerSegment;
        double maxExpectedLoad = 0d;
        Map<PowerPlant, Double> meritOrder;
        double capacitySum;

        MarketInformation(ElectricitySpotMarket market, Map<ElectricitySpotMarket, Double> expectedDemand,
                Map<Substance, Double> fuelPrices, double co2price, long time) {
            // determine expected power prices
            expectedElectricityPricesPerSegment = new HashMap<Segment, Double>();
            Map<PowerPlant, Double> marginalCostMap = new HashMap<PowerPlant, Double>();
            capacitySum = 0d;

            // get merit order for this market
            for (PowerPlant plant : reps.powerPlantRepository.findExpectedOperationalPowerPlantsInMarket(market, time)) {

                double plantMarginalCost = determineExpectedMarginalCost(plant, fuelPrices, co2price);
                marginalCostMap.put(plant, plantMarginalCost);
                capacitySum += plant.getActualNominalCapacity();
            }

            // get difference between technology target and expected operational
            // capacity

            // At this moment no Location present!!! used null
            for (PowerGeneratingTechnologyTarget pggt : reps.powerGenerationTechnologyTargetRepository
                    .findAllByMarket(market)) {
                double expectedTechnologyCapacity = reps.powerPlantRepository
                        .calculateCapacityOfExpectedOperationalPowerPlantsInMarketAndTechnology(market,
                                pggt.getPowerGeneratingTechnology(), time);
                double targetDifference = pggt.getTrend().getValue(time) - expectedTechnologyCapacity;
                if (targetDifference > 0) {
                    PowerPlant plant = new PowerPlant();
                    plant.specifyNotPersist(getCurrentTick(), new EnergyProducer(),
                            reps.powerGridNodeRepository.findFirstPowerGridNodeByElectricitySpotMarket(market),
                            pggt.getPowerGeneratingTechnology(), null);
                    plant.setActualNominalCapacity(targetDifference);
                    double plantMarginalCost = determineExpectedMarginalCost(plant, fuelPrices, co2price);
                    marginalCostMap.put(plant, plantMarginalCost);
                    capacitySum += targetDifference;
                }
            }

            MapValueComparator comp = new MapValueComparator(marginalCostMap);
            meritOrder = new TreeMap<PowerPlant, Double>(comp);
            meritOrder.putAll(marginalCostMap);

            long numberOfSegments = reps.segmentRepository.count();

            double demandFactor = expectedDemand.get(market).doubleValue();

            // find expected prices per segment given merit order
            for (SegmentLoad segmentLoad : market.getLoadDurationCurve()) {

                double expectedSegmentLoad = segmentLoad.getBaseLoad() * demandFactor;

                if (expectedSegmentLoad > maxExpectedLoad) {
                    maxExpectedLoad = expectedSegmentLoad;
                }

                double segmentSupply = 0d;
                double segmentPrice = 0d;
                double totalCapacityAvailable = 0d;

                for (Entry<PowerPlant, Double> plantCost : meritOrder.entrySet()) {
                    PowerPlant plant = plantCost.getKey();
                    double plantCapacity = 0d;
                    // Determine available capacity in the future in this
                    // segment
                    plantCapacity = plant
                            .getExpectedAvailableCapacity(time, segmentLoad.getSegment(), numberOfSegments);
                    totalCapacityAvailable += plantCapacity;
                    // logger.warn("Capacity of plant " + plant.toString() +
                    // " is " +
                    // plantCapacity/plant.getActualNominalCapacity());
                    if (segmentSupply < expectedSegmentLoad) {
                        segmentSupply += plantCapacity;
                        segmentPrice = plantCost.getValue();
                    }

                }

                // logger.warn("Segment " +
                // segmentLoad.getSegment().getSegmentID() + " supply equals " +
                // segmentSupply + " and segment demand equals " +
                // expectedSegmentLoad);

                // Find strategic reserve operator for the market.
                double reservePrice = 0;
                double reserveVolume = 0;
                for (StrategicReserveOperator operator : strategicReserveOperatorRepository.findAll()) {
                    ElectricitySpotMarket market1 = reps.marketRepository.findElectricitySpotMarketForZone(operator
                            .getZone());
                    if (market.getNodeId().intValue() == market1.getNodeId().intValue()) {
                        reservePrice = operator.getReservePriceSR();
                        reserveVolume = operator.getReserveVolume();
                    }
                }

                if (segmentSupply >= expectedSegmentLoad
                        && ((totalCapacityAvailable - expectedSegmentLoad) <= (reserveVolume))) {
                    expectedElectricityPricesPerSegment.put(segmentLoad.getSegment(), reservePrice);
                    // logger.warn("Price: "+
                    // expectedElectricityPricesPerSegment);
                } else if (segmentSupply >= expectedSegmentLoad
                        && ((totalCapacityAvailable - expectedSegmentLoad) > (reserveVolume))) {
                    expectedElectricityPricesPerSegment.put(segmentLoad.getSegment(), segmentPrice);
                    // logger.warn("Price: "+
                    // expectedElectricityPricesPerSegment);
                } else {
                    expectedElectricityPricesPerSegment.put(segmentLoad.getSegment(), market.getValueOfLostLoad());
                }

            }
        }
    }

}
