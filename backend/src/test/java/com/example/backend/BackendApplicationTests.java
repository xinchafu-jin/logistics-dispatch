package com.example.backend;

import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import org.junit.jupiter.api.Test;
import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
class BackendApplicationTests {

    @Test
    void contextLoads() {
        Loader.loadNativeLibraries();
        System.out.println("成功");

        CpModel cpModel = new CpModel();
        IntVar x = cpModel.newIntVar(0, 10, "x");
        IntVar y = cpModel.newIntVar(0, 10, "y");


        // 4. 加入限制條件: x + y <= 10
        cpModel.addLessThan(LinearExpr.sum(new IntVar[] {x, y}), 11);
        // 5. 設定目標函數: 最大化 2*x + 3*y
//  新版正確語法
        cpModel.maximize(LinearExpr.weightedSum(new IntVar[] {x, y}, new long[] {2, 3}));

        // 6. 執行求解
        CpSolver solver = new CpSolver();
        CpSolverStatus status = solver.solve(cpModel);

        // 7. 輸出結果
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            System.out.println("x = " + solver.value(x));
            System.out.println("y = " + solver.value(y));
        }
    }




    }
